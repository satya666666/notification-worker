# Notification Worker MVP Implementation Plan

> **For agentic workers:** After approval, save this plan to [`docs/superpowers/plans/2026-08-10-notification-worker.md`](docs/superpowers/plans/2026-08-10-notification-worker.md), then implement task-by-task. Spec: [`docs/superpowers/specs/2026-08-10-notification-worker-design.md`](docs/superpowers/specs/2026-08-10-notification-worker-design.md).

**Goal:** Ship a working single-process Spring Boot app that accepts notification jobs over HTTP, enqueues them on RabbitMQ, delivers email via `EmailSender`, and tracks status in Postgres with retries + DLQ.

**Architecture:** One Spring Boot process hosts REST + AMQP listener. `POST` persists `PENDING` then publishes `{ notificationId }`; the listener loads the row (idempotent if `SENT`/`FAILED`), sends mail, updates status; exhausted failures route to DLQ and mark `FAILED`.

**Tech Stack:** Java 21 · Spring Boot 3.4.x · Maven · Spring Web · Spring Data JPA · Flyway · Spring AMQP · Spring Mail (optional SMTP) · Docker Compose · JUnit 5 · Mockito · Testcontainers (Postgres + RabbitMQ)

## Global Constraints

- Single deployable (API + listener in one process)
- Message payload is only `{ notificationId }`; DB is source of truth
- Default email provider: `logging`; SMTP via config switch
- Max delivery attempts: config default **3**
- Publish failure after DB insert → mark row `FAILED` with clear error
- No auth, outbox, templates, or separate worker process

## Locked implementation choices

| Decision | Choice |
|---|---|
| Build | Maven (`mvn spring-boot:run` as in spec) |
| Persistence | Spring Data JPA + Flyway (`V1__notification.sql`) |
| Package root | `com.satyam.notificationworker` |
| Retry / DLQ | DB `attempts` + rethrow to requeue while `attempts < max`; on exhaustion mark `FAILED` and throw `AmqpRejectAndDontRequeueException` so RabbitMQ DLX delivers to `notifications.send.dlq` |
| Queue topology | Direct exchange `notifications` · queue `notifications.send` (DLX `notifications.dlx`) · DLQ `notifications.send.dlq` bound via `notifications.dlx` |

```mermaid
sequenceDiagram
  participant Client
  participant API as NotificationController
  participant Svc as NotificationService
  participant DB as Postgres
  participant Q as RabbitMQ
  participant L as NotificationListener
  participant Mail as EmailSender

  Client->>API: POST /notifications
  API->>Svc: create
  Svc->>DB: INSERT PENDING
  Svc->>Q: publish notificationId
  API-->>Client: 202 id PENDING
  Q->>L: consume
  L->>DB: load; skip if SENT or FAILED
  L->>DB: PROCESSING attempts++
  L->>Mail: send
  alt success
    L->>DB: SENT
  else attempts less than max
    L->>DB: last_error
    L-->>Q: nack requeue
  else exhausted
    L->>DB: FAILED
    L-->>Q: reject to DLQ
  end
  Client->>API: GET /notifications/id
  API->>DB: findById
  API-->>Client: 200 status body
```

## Target file map

```
notification-worker/
  pom.xml
  docker-compose.yml
  src/main/java/com/satyam/notificationworker/
    NotificationWorkerApplication.java
    api/
      NotificationController.java
      CreateNotificationRequest.java
      CreateNotificationResponse.java
      NotificationResponse.java
      GlobalExceptionHandler.java
    domain/
      Notification.java
      NotificationStatus.java
    repo/
      NotificationRepository.java
    service/
      NotificationService.java
    messaging/
      NotificationMessage.java
      NotificationListener.java
      RabbitConfig.java
    email/
      EmailSender.java
      LoggingEmailSender.java
      SmtpEmailSender.java
      EmailConfig.java
    config/
      NotificationProperties.java
  src/main/resources/
    application.yml
    db/migration/V1__notification.sql
  src/test/java/... (unit + @SpringBootTest Testcontainers)
  README.md (update)
```

## Task breakdown

### Task 1: Maven skeleton + Docker Compose + config

- Create `pom.xml`: Spring Boot 3.4 parent; deps `web`, `data-jpa`, `flyway-core`, `flyway-database-postgresql`, `postgresql`, `amqp`, `mail`, `validation`; test deps `spring-boot-starter-test`, `spring-rabbit-test`, Testcontainers BOM (`postgresql`, `rabbitmq`, `junit-jupiter`).
- `docker-compose.yml`: Postgres `15` on `5432` (db/user/pass `notification`), RabbitMQ `3-management` on `5672`/`15672`.
- `application.yml`: datasource, JPA (`ddl-auto: validate`), RabbitMQ, `notification.max-attempts: 3`, `notification.email.provider: logging`, SMTP placeholders from env.
- `NotificationProperties` (`@ConfigurationProperties(prefix = "notification")`) with `maxAttempts` and nested email provider settings.
- Empty `@SpringBootApplication` that runs.

### Task 2: Domain + Flyway + repository

- Enum `NotificationStatus`: `PENDING`, `PROCESSING`, `SENT`, `FAILED`.
- Entity `Notification` matching spec columns (`id` UUID, `toEmail`, `subject`, `body`, `status`, `attempts`, `lastError`, timestamps).
- Flyway `V1__notification.sql` with same columns and indexes if useful (`status`).
- `NotificationRepository extends JpaRepository<Notification, UUID>`.

### Task 3: EmailSender + config switch

- Interface `EmailSender { void send(String to, String subject, String body); }`.
- `LoggingEmailSender` `@Primary` / active when `provider=logging` — logs at INFO.
- `SmtpEmailSender` uses `JavaMailSender` when `provider=smtp`.
- `EmailConfig` `@ConditionalOnProperty` beans so only one active; tests never need real SMTP.

### Task 4: RabbitMQ topology + publish path

- `RabbitConfig`: declare exchange/queues/bindings/DLX as above; `Jackson2JsonMessageConverter`; `RabbitTemplate`.
- `NotificationMessage` record: `UUID notificationId`.
- `NotificationService.create(...)`: validate via Bean Validation on DTO; save `PENDING`; publish message; on publish exception update `FAILED` + `lastError` and rethrow or return failed state per spec (mark `FAILED`).
- `NotificationService.get(id)`: Optional → 404 via exception handler.

### Task 5: HTTP API

- `POST /notifications` → `202` `{ id, status }`.
- `GET /notifications/{id}` → `200` full status DTO (camelCase JSON; map `lastError`, etc.).
- Request validation: `@NotBlank` + `@Email` on `to`.
- `GlobalExceptionHandler`: `MethodArgumentNotValidException` → `400`; not-found → `404`.

### Task 6: Listener (retries, idempotency, DLQ)

`NotificationListener` on `notifications.send`:

1. Load by id; missing → reject to DLQ (log error).
2. If `SENT` or `FAILED` → return (ack / no-op).
3. Set `PROCESSING`, `attempts++`, save.
4. Call `EmailSender.send`.
5. Success → `SENT`, `sentAt=now`, clear `lastError`.
6. Failure → set `lastError`; if `attempts < maxAttempts` throw runtime to **requeue**; else set `FAILED` and throw `AmqpRejectAndDontRequeueException` (DLQ).

Unit-test this logic with mocked repo + sender (idempotent, retry, terminal failure).

### Task 7: Integration tests

- `@SpringBootTest` + Testcontainers Postgres + RabbitMQ (`@Testcontainers` / `@ServiceConnection` or DynamicPropertySource).
- Happy path: POST → poll GET until `SENT` (logging sender).
- Failure path: `@MockBean`/`@TestConfiguration` `EmailSender` that always throws → assert eventual `FAILED` and message on DLQ (optional Rabbit admin assert).
- Idempotency: force second delivery of same id after `SENT` → sender invoked once.

### Task 8: README + local demo polish

- Document compose up, `mvn spring-boot:run`, curl examples, `logging` vs `smtp` env/keys, success criteria checklist.
- Confirm `.gitignore` covers `target/`, `.env`, IDE files (already present).

## Verification (done when)

1. `docker compose up -d` starts Postgres + RabbitMQ
2. `mvn test` green (no real SMTP)
3. Manual: POST → GET shows `SENT` with logging provider
4. Forced failures → `FAILED` + DLQ after 3 attempts
5. README matches running commands

## Post-approval execution order

1. Write plan file under `docs/superpowers/plans/`
2. Implement Tasks 1→8 in order (commit at natural milestones if you ask)
3. Run full test suite and a quick local smoke against Compose
