# Notification Worker — Complete Project Document

**Project:** `notification-worker`  
**Package:** `com.satyam.notificationworker`  
**Date:** August 2026  
**Type:** Resume / interview showcase backend (async job processing)

---

## 1. What this project is

**Notification Worker** is a single Spring Boot application that accepts an email notification request over HTTP, stores it in PostgreSQL, puts a job on RabbitMQ, and processes that job asynchronously (in the same process) with retries and a dead-letter queue (DLQ).

It is **not** a simple CRUD API. The point of the project is to demonstrate real backend concepts interviewers care about:

| Concept | How this project shows it |
|---|---|
| Async processing | HTTP returns immediately; work happens later via a queue |
| Message queues | RabbitMQ exchange → queue → listener |
| At-least-once delivery | Messages can be redelivered; listener is idempotent |
| Retries | Failed email sends requeue until max attempts |
| Dead-letter queue (DLQ) | Exhausted failures land on `notifications.send.dlq` |
| Source of truth in DB | Queue message only carries `{ notificationId }` |
| Swappable integrations | `EmailSender` interface: logging (default) or SMTP |
| Integration testing | Testcontainers for real Postgres + RabbitMQ |

**Default email mode is `logging`.** Locally you do not need a real SMTP server — the app “sends” by writing to logs. You can switch to real SMTP with config.

---

## 2. Problem it solves (in plain language)

Imagine an order service wants to email a user:

1. Calling SMTP inside the HTTP request is slow and fragile.
2. If the mail server is down, the whole API call fails.
3. You still want to know whether the email was sent, retried, or permanently failed.

This project’s pattern:

1. Client says: “Please send this email.”
2. API saves the job as `PENDING` and returns `202 Accepted` with an `id`.
3. A background listener picks up the job from RabbitMQ and tries to send.
4. Client can poll `GET /notifications/{id}` to see `PENDING` → `PROCESSING` → `SENT` or `FAILED`.

---

## 3. Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | Java 21 | Modern LTS; common for SDE-1 Java roles |
| Framework | Spring Boot 3.4 | Web, JPA, AMQP, Mail, Validation |
| Build | Maven | `mvn spring-boot:run`, `mvn test` |
| Database | PostgreSQL 15 | Durable job status |
| Schema migration | Flyway | `V1__notification.sql` |
| Broker | RabbitMQ 3 (management image) | Queues, DLX/DLQ, UI at `:15672` |
| Local deps | Docker Compose | One command for Postgres + RabbitMQ |
| Tests | JUnit 5, Mockito, Awaitility, Testcontainers | Unit + real infra integration tests |

---

## 4. High-level architecture

Everything runs in **one Spring Boot process** for MVP simplicity:

```
Client
  │
  ▼
NotificationController          ← REST API
  │
  ▼
NotificationService             ← save + publish
  ├──────────────► PostgreSQL   (notification row)
  └──────────────► RabbitMQ     ({ notificationId })
                         │
                         ▼
              notifications.send queue
                         │
                         ▼
              NotificationListener
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
     load from DB   EmailSender    update status
     (idempotent)   (log / SMTP)   SENT / FAILED
                         │
              on retry exhaustion
                         ▼
              notifications.send.dlq
```

**Important design choice:** the queue message does **not** carry email content. It only carries:

```json
{ "notificationId": "<uuid>" }
```

The database row is the source of truth for `to`, `subject`, `body`, `status`, `attempts`, and errors. That way redelivery always uses the latest DB state.

---

## 5. End-to-end flow (happy path)

1. Client calls `POST /notifications` with `{ to, subject, body }`.
2. Request is validated (`@NotBlank`, `@Email` on `to`).
3. `NotificationService` inserts a row with status `PENDING`, attempts `0`.
4. Service publishes `{ notificationId }` to exchange `notifications` with routing key `send`.
5. API returns **`202 Accepted`**: `{ "id": "...", "status": "PENDING" }`.
6. `NotificationListener` consumes from queue `notifications.send`.
7. Listener loads the row; if already `SENT`/`FAILED`, it no-ops (idempotent).
8. Listener sets `PROCESSING`, increments `attempts`, calls `EmailSender.send(...)`.
9. On success → status `SENT`, sets `sentAt`, clears `lastError`.
10. Client polls `GET /notifications/{id}` and sees `SENT`.

Typical timing locally: under one second from `PENDING` to `SENT` with the logging sender.

---

## 6. Failure, retries, and DLQ

### 6.1 Email send failure (transient)

If `EmailSender` throws:

1. Listener saves `lastError`.
2. If `attempts < maxAttempts` (default **3**):
   - Throws a runtime exception so Spring AMQP **requeues** the message.
3. Message is delivered again → attempts become 2, then 3.

### 6.2 Exhausted retries (terminal)

When `attempts` reaches max:

1. Status becomes `FAILED`.
2. Listener throws `AmqpRejectAndDontRequeueException`.
3. RabbitMQ dead-letters the message via DLX `notifications.dlx` into queue `notifications.send.dlq`.

### 6.3 Publish failure after DB insert

If the row is saved but RabbitMQ publish fails:

- Row is marked `FAILED` with `lastError` like `Broker publish failed: ...`
- Client still gets the created notification object (status `FAILED`)

### 6.4 Missing notification id on the queue

If a message refers to an id that does not exist in DB:

- Logged as error
- Rejected without requeue → goes to DLQ

### 6.5 Idempotency

If a message for an already `SENT` (or `FAILED`) notification is redelivered:

- Listener returns immediately
- Email is **not** sent again

That protects against duplicate delivery (common with at-least-once queues).

---

## 7. Status machine

```
PENDING ──► PROCESSING ──► SENT
                │
                └── (retries) ──► FAILED
```

| Status | Meaning |
|---|---|
| `PENDING` | Saved; waiting to be (or being) picked up from queue |
| `PROCESSING` | Listener claimed the job and incremented attempts |
| `SENT` | Email send succeeded (logging or SMTP) |
| `FAILED` | Terminal failure (retries exhausted, or broker publish failed) |

---

## 8. API reference

Base URL (local): `http://localhost:8080`

> There is **no homepage**. Opening `/` shows Spring’s Whitelabel 404. That is normal.

### 8.1 Create notification

```http
POST /notifications
Content-Type: application/json

{
  "to": "user@example.com",
  "subject": "Hi",
  "body": "Hello"
}
```

**Success:** `202 Accepted`

```json
{
  "id": "56311731-26c6-4257-915d-4a725838eea6",
  "status": "PENDING"
}
```

**Validation failure:** `400 Bad Request` (invalid email, blank fields)

### 8.2 Get notification status

```http
GET /notifications/{id}
```

**Success:** `200 OK`

```json
{
  "id": "56311731-26c6-4257-915d-4a725838eea6",
  "to": "a@b.com",
  "subject": "Hi",
  "status": "SENT",
  "attempts": 1,
  "lastError": null,
  "createdAt": "2026-08-10T09:44:58.372955Z",
  "updatedAt": "2026-08-10T09:44:58.410859Z",
  "sentAt": "2026-08-10T09:44:58.408984Z"
}
```

**Unknown id:** `404 Not Found`

Note: GET returns metadata/status fields, not the email `body` (by design in the MVP API contract).

---

## 9. Data model (PostgreSQL)

Table: `notification` (Flyway migration `V1__notification.sql`)

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | Generated in application |
| `to_email` | TEXT NOT NULL | Recipient |
| `subject` | TEXT NOT NULL | Subject line |
| `body` | TEXT NOT NULL | Message body |
| `status` | TEXT NOT NULL | `PENDING` / `PROCESSING` / `SENT` / `FAILED` |
| `attempts` | INT NOT NULL DEFAULT 0 | Delivery attempts so far |
| `last_error` | TEXT | Last failure message (truncated in listener) |
| `created_at` | TIMESTAMPTZ | Set on insert |
| `updated_at` | TIMESTAMPTZ | Updated on each change |
| `sent_at` | TIMESTAMPTZ | Set when status becomes `SENT` |

Index: `idx_notification_status` on `status`.

JPA `ddl-auto` is `validate` — schema comes from Flyway, not Hibernate auto-create.

---

## 10. Messaging topology (RabbitMQ)

Declared in `RabbitConfig`:

| Piece | Name |
|---|---|
| Main exchange | `notifications` (direct) |
| Main queue | `notifications.send` |
| Routing key | `send` |
| Dead-letter exchange | `notifications.dlx` (direct) |
| Dead-letter queue | `notifications.send.dlq` |
| DLQ routing key | `send.dlq` |

Main queue arguments:

- `x-dead-letter-exchange` = `notifications.dlx`
- `x-dead-letter-routing-key` = `send.dlq`

Messages use JSON via `Jackson2JsonMessageConverter`.

**Management UI:** http://localhost:15672  
**Login:** `guest` / `guest`

Useful UI checks:

- Queues → `notifications.send` (ready/unacked/total)
- Queues → `notifications.send.dlq` (after forced failures)

---

## 11. Package / component map

```
com.satyam.notificationworker
├── NotificationWorkerApplication     # Spring Boot entrypoint
├── api/
│   ├── NotificationController        # POST + GET
│   ├── CreateNotificationRequest     # validated input DTO
│   ├── CreateNotificationResponse    # 202 body
│   ├── NotificationResponse          # GET body
│   └── GlobalExceptionHandler        # 400 / 404 ProblemDetail
├── domain/
│   ├── Notification                  # JPA entity
│   └── NotificationStatus            # enum
├── repo/
│   └── NotificationRepository        # Spring Data JPA
├── service/
│   ├── NotificationService           # create + get
│   └── NotificationNotFoundException
├── messaging/
│   ├── RabbitConfig                  # exchanges, queues, bindings
│   ├── NotificationMessage           # queue payload record
│   └── NotificationListener          # consumer + retry/DLQ logic
├── email/
│   ├── EmailSender                   # interface
│   ├── LoggingEmailSender            # default provider
│   ├── SmtpEmailSender               # optional SMTP
│   ├── EmailConfig                   # provider switch marker
│   └── EmailSendException
└── config/
    └── NotificationProperties        # maxAttempts, email settings
```

### Responsibility summary

| Component | Responsibility |
|---|---|
| Controller | HTTP mapping only; no business rules |
| Service | Persist job, publish message, load by id |
| Listener | Consume queue, send email, retry / fail / DLQ |
| Repository | DB access |
| EmailSender | Actual delivery abstraction |
| GlobalExceptionHandler | Consistent API errors |

---

## 12. Email providers

Configured by:

```yaml
notification.email.provider: logging   # or smtp
notification.max-attempts: 3
notification.email.from: noreply@example.com
```

### Logging (default)

- Class: `LoggingEmailSender`
- Behavior: logs recipient/subject/body at INFO
- Perfect for local demo and tests
- No network / SMTP needed

### SMTP (optional)

- Class: `SmtpEmailSender` (Spring `JavaMailSender`)
- Enable with env vars:

```bash
export NOTIFICATION_EMAIL_PROVIDER=smtp
export SMTP_HOST=smtp.example.com
export SMTP_PORT=587
export SMTP_USER=...
export SMTP_PASSWORD=...
export SMTP_AUTH=true
export SMTP_STARTTLS=true
```

Only one provider bean is active based on the property switch.

---

## 13. Configuration defaults

From `application.yml` / Docker Compose:

| Setting | Default |
|---|---|
| App port | `8080` |
| Postgres host port | `5433` → container `5432` |
| DB name / user / password | `notification` / `notification` / `notification` |
| RabbitMQ AMQP | `localhost:5672` |
| RabbitMQ UI | `localhost:15672` |
| RabbitMQ user/pass | `guest` / `guest` |
| Max send attempts | `3` |
| Email provider | `logging` |

Postgres is on **5433** on the host so it does not clash with a local Postgres already using 5432.

---

## 14. How to run locally

### Prerequisites

- JDK **21** (not newer major like 25 for this project setup)
- Docker Desktop running
- Maven

### Steps

```bash
cd /Users/satyamgupta/IdeaProjects/notification-worker

# 1) Start dependencies
docker compose up -d

# 2) Start the app
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn spring-boot:run
```

Wait for: `Started NotificationWorkerApplication`

### Demo curl

```bash
# Create
curl -s -X POST http://localhost:8080/notifications \
  -H 'Content-Type: application/json' \
  -d '{"to":"a@b.com","subject":"Hi","body":"Hello"}'

# Example response:
# {"id":"<uuid>","status":"PENDING"}

# Check status (paste the real uuid)
curl -s http://localhost:8080/notifications/<uuid>
```

### What success looks like

1. POST returns `PENDING`
2. App logs show publish + send
3. GET returns `status: SENT`, `attempts: 1`, `sentAt` set
4. RabbitMQ queue `notifications.send` stays empty / consumed

---

## 15. Testing strategy

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn test
```

Expected: **14 tests**, `BUILD SUCCESS` (no real SMTP required).

| Test class | What it covers |
|---|---|
| `NotificationListenerTest` | Idempotent skip, retry requeue, terminal DLQ reject, missing id |
| `NotificationServiceTest` | Publish success; publish failure marks `FAILED` |
| `NotificationIntegrationTest` | Full stack with Testcontainers Postgres + RabbitMQ: happy path → `SENT`, forced failures → `FAILED` + DLQ message, redelivery idempotency, `400` / `404` |

Integration tests spin up real containers; Docker must be available.

---

## 16. What is intentionally out of scope (MVP)

These are good **interview talking points** for “what would you build next?”:

1. Split API and worker into two deployables
2. Transactional outbox (stronger publish reliability)
3. Auth / multi-tenant history
4. Email templates, attachments, multi-channel (SMS/push)
5. Metrics / tracing (queue depth, latency, failure rate)
6. Priority or delayed messages
7. Admin UI

---

## 17. Interview / resume one-liners

You can describe this project as:

> “Built an async notification service in Java 21 / Spring Boot that accepts HTTP jobs, enqueues work on RabbitMQ, processes emails with retries and a DLQ, persists status in PostgreSQL, and proves the flow with Testcontainers integration tests.”

Key words to emphasize:

- Asynchronous processing
- RabbitMQ exchanges, queues, DLX/DLQ
- Idempotent consumers
- Retry semantics tied to DB `attempts`
- Interface-based email provider
- Flyway + JPA
- Docker Compose local environment
- Testcontainers

---

## 18. Related docs in this repo

| File | Purpose |
|---|---|
| [README.md](../README.md) | Quick start, API table, config cheat sheet |
| [docs/superpowers/specs/2026-08-10-notification-worker-design.md](superpowers/specs/2026-08-10-notification-worker-design.md) | Approved design / MVP scope |
| [docs/superpowers/plans/2026-08-10-notification-worker.md](superpowers/plans/2026-08-10-notification-worker.md) | Implementation plan (Tasks 1–8) |
| **This file** | Full narrative project overview |

---

## 19. Quick mental model (one paragraph)

A client posts an email job; the API writes a `PENDING` row and publishes only the row’s id to RabbitMQ; a listener in the same app consumes that id, loads content from Postgres, tries to send mail (log or SMTP), updates status, retries up to three times on failure, and finally marks `FAILED` while dead-lettering the message — and if the same id is delivered again after `SENT`, nothing is sent twice.
