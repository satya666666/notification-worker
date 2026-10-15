# Notification Worker — Full Project Guide

**Project:** `notification-worker`  
**Package:** `com.satyam.notificationworker`  
**Date:** 2026-08-18 (MVP + outbox / backoff / split / metrics)  
**Related docs:** [Design Spec](superpowers/specs/2026-08-10-notification-worker-design.md) · [Implementation Plan](superpowers/plans/2026-08-10-notification-worker.md) · [README](../README.md)

---

## 1. What this project is

**Notification Worker** is a backend service that accepts an email-notification job over HTTP, stores it in **PostgreSQL** with a **transactional outbox**, publishes a lightweight message to **RabbitMQ**, and has a **worker listener** (same JAR, optional separate process) that sends the email and updates status.

It is built as a **resume / interview showcase** project. The point is not fancy email templates — it is to demonstrate real backend concepts:

| Concept | How this project shows it |
|---|---|
| Async processing | HTTP returns quickly; work happens on a queue |
| Transactional outbox | Job + outbox row in one TX; poller publishes after commit |
| Message queues | RabbitMQ exchange + queue + retry TTL queue |
| Durability / status tracking | Postgres row is source of truth |
| At-least-once delivery | Messages can be redelivered |
| Idempotency | Already `SENT` / `FAILED` → no double-send |
| Delayed retries | Transient failures wait with exponential backoff |
| Dead Letter Queue (DLQ) | Exhausted failures land on `notifications.send.dlq` |
| Split deployables | Same JAR; `api` vs `worker` roles |
| Metrics | Actuator + Micrometer `notifications.*` counters |
| Swappable integrations | `EmailSender` = logging (default) or SMTP |

**One-liner for interviews:**  
> “HTTP accepts a job, Postgres tracks status, RabbitMQ decouples delivery with retries and a DLQ, and the listener is idempotent under at-least-once delivery.”

---

## 2. What problem it solves

A naive API might send email **inside** the HTTP request:

```
Client → API → SMTP → response
```

Problems:

- Slow or flaky SMTP blocks the API
- Client timeouts / retries can double-send
- Hard to track “did it actually send?”
- No clean place for retries / failure inspection

This project uses the **job queue** pattern:

```
Client → API → save PENDING → publish id to RabbitMQ → 202 Accepted
                              ↓
                         Worker listener
                              ↓
                    load job → send email → SENT / FAILED
```

Clients can poll `GET /notifications/{id}` for status instead of waiting on SMTP.

---

## 3. Tech stack

| Layer | Choice | Version / notes |
|---|---|---|
| Language | Java | **21** |
| Framework | Spring Boot | **3.4.2** |
| Build | Maven | `pom.xml` |
| HTTP | Spring Web + Validation | Bean Validation on DTOs |
| Persistence | Spring Data JPA + Flyway | Schema owned by migrations |
| Database | PostgreSQL | **15** (Compose) |
| Messaging | Spring AMQP / RabbitMQ | **3-management** (Compose) |
| Email | Spring Mail | Optional; logging by default |
| Local deps | Docker Compose | Postgres + RabbitMQ + optional API/worker images |
| Observability | Spring Boot Actuator | `/actuator/health`, `/actuator/metrics` |
| Tests | JUnit 5, Mockito, Testcontainers | **1.20.6** |

Artifact coordinates: `com.satyam:notification-worker:0.0.1-SNAPSHOT`

---

## 4. High-level architecture

The same JAR can run **both roles** (local Maven) or **split** into API + worker containers:

1. REST API + outbox publisher (accept + query jobs, publish after commit)
2. RabbitMQ listener (process jobs)

```
┌──────────────────────── API (8080) ─────────────────────────┐
│  NotificationController                                     │
│           │                                                 │
│           ▼                                                 │
│  NotificationService                                        │
│     └─ save PENDING + outbox row ──────────▶ PostgreSQL     │
│                                                             │
│  OutboxPublisher (scheduled)                                │
│     └─ publish { notificationId } ─────────▶ RabbitMQ       │
└─────────────────────────────────────────────────────────────┘
                         │
                         ▼
              notifications.send queue
                         │
                         ▼
┌────────────────────── Worker (8081) ────────────────────────┐
│  NotificationListener                                       │
│     ├─ load row / skip if SENT|FAILED                       │
│     ├─ PROCESSING + attempts++                              │
│     ├─ EmailSender.send(...)                                │
│     └─ SENT  or  delayed retry  or  FAILED → DLQ            │
└─────────────────────────────────────────────────────────────┘
```

### End-to-end sequence

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
  API->>Svc: create(to, subject, body)
  Svc->>DB: INSERT PENDING + outbox row
  API-->>Client: 202 { id, PENDING }
  Note over Svc,Q: OutboxPublisher (after commit)
  Svc->>Q: publish { notificationId }

  Q->>L: deliver message
  L->>DB: load row
  L->>DB: PROCESSING, attempts++
  L->>Mail: send(...)
  alt success
    L->>DB: SENT + sentAt
  else attempts less than max
    L->>DB: lastError
    L-->>Q: retry queue (TTL backoff)
  else exhausted
    L->>DB: FAILED
    L-->>Q: reject → DLQ
  end

  Client->>API: GET /notifications/{id}
  API->>DB: findById
  API-->>Client: 200 status JSON
```

### Important design choice: transactional outbox

`NotificationService.create` writes the notification row **and** an `notification_outbox` row in **one database transaction**. A scheduled `OutboxPublisher` (API role) then publishes `{ notificationId }` and marks the outbox row published.

Why not publish inside the HTTP request:

- Publishing inside an open DB transaction races the listener against an uncommitted row.
- Publishing after commit without an outbox can lose the job if RabbitMQ is down.

If the broker is down, the row stays `PENDING` and the poller retries until publish succeeds. Duplicate publishes are safe because the listener is idempotent.

---

## 5. Project structure

```
notification-worker/
├── pom.xml
├── Dockerfile                  # Multi-stage Maven → JRE image
├── docker-compose.yml          # Postgres + RabbitMQ + api + worker
├── .github/workflows/ci.yml    # mvn test on push/PR
├── README.md                   # Quick start
├── docs/
│   ├── PROJECT-OVERVIEW.md     # This document
│   └── superpowers/
│       ├── specs/...-design.md
│       └── plans/...-worker.md
└── src/
    ├── main/
    │   ├── java/com/satyam/notificationworker/
    │   │   ├── NotificationWorkerApplication.java
    │   │   ├── api/            # HTTP layer
    │   │   ├── config/         # App properties
    │   │   ├── domain/         # Entity + status enum
    │   │   ├── repo/           # Spring Data repository
    │   │   ├── service/        # Business orchestration
    │   │   ├── messaging/      # RabbitMQ topology, outbox publisher, listener
    │   │   ├── metrics/        # Micrometer counters
    │   │   └── email/          # EmailSender implementations
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/V1__notification.sql, V2__outbox.sql
    └── test/java/...           # Unit + Testcontainers integration tests
```

---

## 6. Package-by-package walkthrough

### 6.1 `api` — HTTP layer

| Class | Role |
|---|---|
| `NotificationController` | Maps `POST /notifications` and `GET /notifications/{id}` |
| `CreateNotificationRequest` | Incoming JSON: `to`, `subject`, `body` (+ validation) |
| `CreateNotificationResponse` | Outgoing create response: `id`, `status` |
| `NotificationResponse` | Full status DTO for GET (does **not** include email body) |
| `GlobalExceptionHandler` | Validation → **400**; missing id → **404** (ProblemDetail) |

### 6.2 `service` — orchestration

| Class | Role |
|---|---|
| `NotificationService` | Persist job + outbox row, load by id |
| `NotificationNotFoundException` | Thrown when GET id does not exist |

### 6.3 `domain` + `repo` — persistence

| Class | Role |
|---|---|
| `Notification` | JPA entity mapped to table `notification` |
| `OutboxEvent` | JPA entity mapped to table `notification_outbox` |
| `NotificationStatus` | Enum: `PENDING`, `PROCESSING`, `SENT`, `FAILED` |
| `NotificationRepository` | `JpaRepository<Notification, UUID>` |
| `OutboxEventRepository` | Unpublished outbox poll with pessimistic lock |

### 6.4 `messaging` — RabbitMQ

| Class | Role |
|---|---|
| `RabbitConfig` | Declares exchange, queue, retry queue, DLX, DLQ, bindings |
| `NotificationMessage` | Tiny payload: `{ notificationId }` |
| `OutboxPublisher` | Scheduled poller; publishes unpublished outbox rows |
| `NotificationListener` | Consumes queue; send + delayed retry + idempotency + DLQ |

### 6.5 `email` — delivery

| Class | Role |
|---|---|
| `EmailSender` | Interface: `void send(to, subject, body)` |
| `LoggingEmailSender` | Default; logs the email (no network) |
| `SmtpEmailSender` | Real SMTP via Spring `JavaMailSender` |
| `EmailSendException` | Runtime wrapper for SMTP failures |
| `EmailConfig` | Documents / organizes the provider switch |

### 6.6 `config`

| Class | Role |
|---|---|
| `NotificationProperties` | max-attempts, retry backoff, roles, outbox, email |

### 6.7 `metrics`

| Class | Role |
|---|---|
| `NotificationMetrics` | Counters: created, published, sent, failed, retried |

---

## 7. Data model (Postgres)

Flyway migrations: `V1__notification.sql`, `V2__outbox.sql`  
JPA setting: `ddl-auto: validate` (Hibernate does **not** create tables; Flyway owns schema).

### Table `notification`

| Column | Type | Meaning |
|---|---|---|
| `id` | UUID PK | Job id returned to the client |
| `to_email` | TEXT NOT NULL | Recipient |
| `subject` | TEXT NOT NULL | Subject line |
| `body` | TEXT NOT NULL | Email body (stored; not returned on GET) |
| `status` | TEXT NOT NULL | `PENDING` / `PROCESSING` / `SENT` / `FAILED` |
| `attempts` | INT DEFAULT 0 | How many times the listener tried to send |
| `last_error` | TEXT | Last failure message (truncated in listener) |
| `created_at` | TIMESTAMPTZ | Created |
| `updated_at` | TIMESTAMPTZ | Last update |
| `sent_at` | TIMESTAMPTZ | Set when status becomes `SENT` |

Index: `idx_notification_status` on `status`.

### Table `notification_outbox`

| Column | Type | Meaning |
|---|---|---|
| `id` | UUID PK | Outbox event id |
| `notification_id` | UUID FK | Job to publish |
| `created_at` | TIMESTAMPTZ | Inserted with the job |
| `published_at` | TIMESTAMPTZ | Set after RabbitMQ publish succeeds |

Partial index `idx_outbox_unpublished` on `created_at` where `published_at IS NULL`.

### Status lifecycle

```
PENDING ──▶ PROCESSING ──▶ SENT
                 │
                 └──(retries exhausted)──▶ FAILED
```

- `PENDING` — saved, waiting for worker (or just published)
- `PROCESSING` — listener picked it up; may delay-retry while still under max attempts
- `SENT` — terminal success
- `FAILED` — terminal failure (email retries exhausted)

---

## 8. API reference

Base URL: `http://localhost:8080`

There is **no homepage** at `/`. Opening the root URL shows Spring’s Whitelabel 404 — that is normal.

### `POST /notifications`

**Request**

```json
{
  "to": "a@b.com",
  "subject": "Hi",
  "body": "Hello"
}
```

**Validation**

- `to`, `subject`, `body` must be non-blank
- `to` must look like an email (`@Email`)

**Success — `202 Accepted`**

```json
{
  "id": "56311731-26c6-4257-915d-4a725838eea6",
  "status": "PENDING"
}
```

**Invalid body — `400`**

**Note:** Publish happens from the outbox after commit. A broker outage leaves the job `PENDING` until the poller succeeds.

### `GET /notifications/{id}`

**Success — `200`**

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

**Unknown id — `404`**

---

## 9. Messaging design (RabbitMQ)

Configured in `RabbitConfig`.

| Piece | Name |
|---|---|
| Main exchange | `notifications` (direct) |
| Routing key | `send` |
| Work queue | `notifications.send` |
| Dead-letter exchange | `notifications.dlx` (direct) |
| DLQ routing key | `send.dlq` |
| Dead-letter queue | `notifications.send.dlq` |
| Retry exchange | `notifications.retry` (direct) |
| Retry routing key | `send.retry` |
| Retry queue | `notifications.send.retry` (per-message TTL → main exchange) |

**Message body (only):**

```json
{ "notificationId": "<uuid>" }
```

Why so small?

- Content lives in Postgres (source of truth)
- Listener always reloads latest status / attempts
- Avoids stale or oversized queue payloads

**Management UI:** http://localhost:15672  
**Login:** `guest` / `guest`

---

## 10. Listener behavior (retries, idempotency, DLQ)

Implemented in `NotificationListener` with `notification.max-attempts` (default **3**).

### Step-by-step

1. Receive `NotificationMessage`
2. Load notification by id  
   - Missing → reject without requeue → **DLQ**
3. If status is already `SENT` or `FAILED` → **return** (ack, no-op)  
   - This is **idempotency** for duplicate deliveries
4. Set `PROCESSING`, increment `attempts`, save
5. Call `EmailSender.send(...)`
6. **On success** → `SENT`, set `sentAt`, clear `lastError`
7. **On failure**
   - Save `lastError`
   - If `attempts < maxAttempts` → publish to the **retry queue** with per-message TTL (exponential backoff), then **ack**
   - If exhausted → set `FAILED` → throw `AmqpRejectAndDontRequeueException` → RabbitMQ routes to **DLQ**

### Mental model

| Outcome | DB status | Queue action |
|---|---|---|
| First success | `SENT` | Ack |
| Transient fail (attempt 1 or 2) | `PROCESSING` + `lastError` | Retry queue with TTL, then back to `send` |
| Fail on attempt 3 | `FAILED` | Reject → DLQ |
| Redeliver after `SENT` | unchanged | Ack / skip send |
| Unknown id | n/a | Reject → DLQ |

Retries are counted in the **database** (`attempts`). Backoff is `initial * multiplier^(attempt-1)`, capped at `max-backoff-ms` (default 1s, 2s, 4s…).

---

## 11. Email providers

Controlled by `notification.email.provider`.

### `logging` (default)

- Class: `LoggingEmailSender`
- Does **not** send real email
- Logs at INFO, e.g.  
  `Email sent (logging provider): to=..., subject=..., body=...`
- Perfect for local demo and tests

### `smtp`

- Class: `SmtpEmailSender`
- Uses Spring Mail / `JavaMailSender`
- Needs SMTP env vars (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, etc.)
- From address: `notification.email.from` (default `noreply@example.com`)

Switch example:

```bash
export NOTIFICATION_EMAIL_PROVIDER=smtp
export SMTP_HOST=smtp.example.com
export SMTP_PORT=587
export SMTP_USER=...
export SMTP_PASSWORD=...
export SMTP_AUTH=true
export SMTP_STARTTLS=true
mvn spring-boot:run
```

---

## 12. Configuration & local infrastructure

### Docker Compose (`docker-compose.yml`)

| Service | Image | Host ports | Credentials / role |
|---|---|---|---|
| Postgres | `postgres:15` | **5433** → 5432 | db/user/pass = `notification` |
| RabbitMQ | `rabbitmq:3-management` | **5672**, **15672** | `guest` / `guest` |
| `api` | local Dockerfile | **8080** | API + outbox publisher |
| `worker` | local Dockerfile | **8081** → 8080 | Listener only |

Postgres uses **5433** on the host so it does not clash with a local Postgres on 5432.

`docker compose up --build` starts the full stack. For local Maven, start only `postgres` and `rabbitmq` (both roles stay enabled in the single process).

### Key `application.yml` settings

| Setting | Default |
|---|---|
| `server.port` | `8080` |
| DB URL | `localhost:5433` / database `notification` |
| RabbitMQ | `localhost:5672`, guest/guest |
| `notification.max-attempts` | `3` |
| `notification.retry.initial-backoff-ms` | `1000` |
| `notification.roles.api` / `worker` | `true` / `true` |
| `notification.email.provider` | `logging` |

---

## 13. How to run (demo walkthrough)

### Prerequisites

- **JDK 21** (not 25+)
- Docker Desktop running

### Full stack

```bash
cd /Users/satyamgupta/IdeaProjects/notification-worker
docker compose up --build
```

### Or local Maven (single process)

```bash
docker compose up -d postgres rabbitmq
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn spring-boot:run
```

Wait for: `Started NotificationWorkerApplication`

### Create a job

```bash
curl -s -X POST http://localhost:8080/notifications \
  -H 'Content-Type: application/json' \
  -d '{"to":"a@b.com","subject":"Hi","body":"Hello"}'
```

Example response:

```json
{"id":"56311731-26c6-4257-915d-4a725838eea6","status":"PENDING"}
```

### Check status

```bash
curl -s http://localhost:8080/notifications/56311731-26c6-4257-915d-4a725838eea6
```

Within about a second (logging provider), expect `"status":"SENT"`.

### What you should see in logs

1. `Queued notification id=... via outbox`
2. `Published outbox event id=...`
3. `Email sent (logging provider): to=a@b.com, subject=Hi, body=Hello`
4. `Notification sent id=... attempts=1`

### Optional checks in RabbitMQ UI

1. Open http://localhost:15672 (`guest`/`guest`)
2. Queues → `notifications.send` (messages get consumed quickly)
3. On forced failures, messages appear in `notifications.send.dlq`

---

## 14. Testing strategy

Run:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn test
```

Expect **18 tests**, `BUILD SUCCESS`. No real SMTP required.

| Test class | Type | What it covers |
|---|---|---|
| `NotificationListenerTest` | Unit (Mockito) | Idempotent skip, success, delayed retry, exhausted → DLQ, missing row |
| `NotificationServiceTest` | Unit (Mockito) | Persist + outbox write, not-found |
| `OutboxPublisherTest` | Unit (Mockito) | Publish + mark; broker failure leaves unpublished |
| `NotificationPropertiesTest` | Unit | Exponential backoff + cap |
| `NotificationIntegrationTest` | Integration (Testcontainers) | Happy path → `SENT`, retries+DLQ, idempotent redelivery, 400/404, health |

Testcontainers spins up real Postgres + RabbitMQ containers for integration tests. Surefire is configured with Docker API **1.44** for Docker Engine 29+.

---

## 15. What is intentionally out of scope (MVP)

These are still good **interview talking points** for what you would build next:

1. Auth / multi-tenant APIs  
2. Email templates, attachments, SMS/push  
3. Admin UI, cron/scheduling, priority queues  
4. Distributed tracing / Prometheus scrape
5. Real provider (SES/SendGrid) + delivery webhooks

Outbox, delayed retries, API/worker split, Actuator metrics, Docker, and CI are implemented.

---

## 16. How the pieces map to interview answers

**Q: Why RabbitMQ instead of calling SMTP in the controller?**  
A: Decouples acceptance from delivery; API stays fast; retries and DLQ are natural; status is pollable.

**Q: Why is the queue payload only an id?**  
A: DB is source of truth. Listener always loads current content and status. Supports idempotency and avoids stale messages.

**Q: How do you avoid double-sending?**  
A: Before sending, if status is already `SENT` or `FAILED`, the listener no-ops. That makes at-least-once delivery safe for terminal states.

**Q: What happens when email keeps failing?**  
A: Each failure increments `attempts`. Under max → publish to a TTL retry queue (exponential backoff). At max → mark `FAILED` and reject to DLQ for inspection.

**Q: How do you avoid “saved but never published”?**  
A: The job and outbox row commit together. A poller publishes after commit and retries if the broker is down. The listener is idempotent if a publish is duplicated.

**Q: How do you run API and worker separately?**  
A: Same JAR. `NOTIFICATION_ROLE_API` / `NOTIFICATION_ROLE_WORKER` toggle the controller+publisher vs the listener. Compose runs two containers.

---

## 17. Success checklist (MVP done when)

- [x] `docker compose up --build` starts Postgres, RabbitMQ, API, and worker  
- [x] `POST /notifications` returns `202` with `PENDING`  
- [x] Outbox poller publishes after commit; `GET` shows `SENT` with logging provider  
- [x] Forced failures exhaust retries (with backoff) → `FAILED` + DLQ  
- [x] Re-delivery after `SENT` does not double-send  
- [x] Actuator health/metrics available  
- [x] README documents run + logging vs SMTP + roles  
- [x] Tests pass without a real SMTP server  
- [x] GitHub Actions CI runs `mvn test`  

---

## 18. Quick reference card

| Item | Value |
|---|---|
| App URL | http://localhost:8080 |
| Create | `POST /notifications` |
| Status | `GET /notifications/{id}` |
| Postgres | localhost:**5433**, user/pass/db `notification` |
| RabbitMQ AMQP | localhost:**5672**, guest/guest |
| RabbitMQ UI | http://localhost:15672 |
| Worker health | http://localhost:8081/actuator/health (Compose) |
| Work queue | `notifications.send` |
| Retry queue | `notifications.send.retry` |
| DLQ | `notifications.send.dlq` |
| Default email mode | `logging` (log only, no real mail) |
| Max attempts | `3` |

---

*This document describes the completed MVP as implemented in the repository. For design rationale see the design spec; for task history see the implementation plan.*
