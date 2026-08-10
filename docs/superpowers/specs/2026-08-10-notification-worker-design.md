# Notification Worker — Design Spec

**Date:** 2026-08-10  
**Status:** Approved design (MVP scope)  
**Author:** Satyam Gupta  
**Location:** `/Users/satyamgupta/IdeaProjects/notification-worker` (sibling to AskMyDocs)

## Purpose

A resume-showcase backend that demonstrates **async processing**: accept a notification request over HTTP, enqueue work on **RabbitMQ**, process it in a worker listener (same Spring Boot process for MVP), deliver email via a swappable **`EmailSender`**, and persist job status in **Postgres** with **retries** and a **dead-letter queue (DLQ)**.

Interview value: queues, at-least-once delivery, idempotency, retries/DLQ, status APIs — not just CRUD.

## Scope

**In scope (MVP):**
- Single Spring Boot 3 / Java 21 application (API + listener in one process).
- `POST /notifications` and `GET /notifications/{id}`.
- Postgres table for notification jobs and status history fields.
- RabbitMQ queue + DLQ; publish `{ notificationId }` after persisting `PENDING`.
- `EmailSender` interface with `LoggingEmailSender` (default) and optional `SmtpEmailSender`.
- Configurable max delivery attempts; failures retry then land on DLQ and mark `FAILED`.
- Docker Compose for app dependencies (Postgres + RabbitMQ).
- Unit + integration tests (Testcontainers where practical).

**Out of scope (deliberate v2 talking points):**
- Auth / multi-tenant.
- Transactional outbox pattern.
- Email templates, attachments, multi-channel (SMS/push).
- Separate deployable worker process / K8s scaling story beyond “can split later”.
- Admin UI, scheduling/cron, priority queues.
- Full observability stack (basic logging is enough for MVP).

## Technology Choices

| Concern | Choice | Rationale |
|---|---|---|
| Language / runtime | Java 21 | Matches target SDE-1 Java roles and AskMyDocs. |
| Framework | Spring Boot 3 + Spring AMQP | Industry standard; first-class RabbitMQ support. |
| Datastore | PostgreSQL | Durable job status; pollable by clients. |
| Broker | RabbitMQ | Simple retries/DLQ story; easy Docker demo. |
| Email | Interface + logging default + optional SMTP | Testable without real mail; production path available. |
| Local orchestration | Docker Compose | One command for Postgres + RabbitMQ. |
| Testing | JUnit 5, Mockito, Testcontainers | Deterministic tests; real broker/DB when needed. |

## Architecture

Single deployable Spring Boot application.

```
  Client
    │
    ▼
  NotificationController
    │
    ▼
  NotificationService
    ├─ INSERT notification (status=PENDING) ──▶ Postgres
    └─ publish { notificationId } ────────────▶ RabbitMQ
                                                      │
                                                      ▼
                                            notifications.send
                                                      │
                                                      ▼
                                            NotificationListener
                                                      │
                              ┌───────────────────────┼───────────────────────┐
                              ▼                       ▼                       ▼
                     load row from DB          EmailSender.send        update status
                     (idempotent)              (Logging | SMTP)        SENT / FAILED
                                                      │
                                         on exhaustion of retries
                                                      ▼
                                            notifications.send.dlq
```

## Components

| Component | Responsibility |
|---|---|
| `NotificationController` | HTTP: create + get status |
| `NotificationService` | Persist job, publish message, query status |
| `NotificationListener` | Consume queue, send email, update status, handle retries |
| `EmailSender` | Interface: `send(to, subject, body)` |
| `LoggingEmailSender` | Default: log “sent” payload (no network) |
| `SmtpEmailSender` | Optional: JavaMail / Spring Mail SMTP |
| `NotificationRepository` | JDBC or Spring Data access to `notification` table |

## Data Model

**`notification`**
- `id` (UUID, PK)
- `to_email` (text, not null)
- `subject` (text, not null)
- `body` (text, not null)
- `status` (text: `PENDING` \| `PROCESSING` \| `SENT` \| `FAILED`)
- `attempts` (int, default 0)
- `last_error` (text, nullable)
- `created_at` (timestamptz)
- `updated_at` (timestamptz)
- `sent_at` (timestamptz, nullable)

No separate “events” table in MVP. Message body on the queue is only the id; the DB is source of truth for content and status.

## API Surface

### `POST /notifications`
- Request: `{ "to": "user@example.com", "subject": "...", "body": "..." }`
- Validation: non-blank `to`, `subject`, `body`; basic email format check on `to`.
- Response `202 Accepted`: `{ "id": "<uuid>", "status": "PENDING" }`

### `GET /notifications/{id}`
- Response `200`: `{ "id", "to", "subject", "status", "attempts", "lastError", "createdAt", "updatedAt", "sentAt" }`
- Unknown id → `404`

## Messaging Design

| Item | Value |
|---|---|
| Exchange | Direct (or default) — keep simple for MVP |
| Main queue | `notifications.send` |
| DLQ | `notifications.send.dlq` |
| Message payload | `{ "notificationId": "<uuid>" }` |
| Max attempts | Config, default **3** |
| Ack mode | Acknowledge after successful DB status update to `SENT`, or after recording terminal `FAILED` / requeue decision per Spring AMQP retry policy |

**Listener behavior:**
1. Receive `notificationId`.
2. Load row; if already `SENT`, ack and no-op (idempotent).
3. If `FAILED` terminal, ack and no-op (do not resurrect unless explicit API later).
4. Set `PROCESSING`, increment `attempts`.
5. Call `EmailSender.send`.
6. On success → `SENT`, set `sent_at`, clear `last_error`.
7. On failure → set `last_error`; if `attempts < max` → requeue/retry; else → publish/route to DLQ and set `FAILED`.

## Email Configuration

| Key | Default / notes |
|---|---|
| `notification.email.provider` | `logging` \| `smtp` |
| SMTP host/port/user/pass | Only required when `smtp` |

Keys live in `application.yml`; secrets from env vars.

## Error Handling

| Case | Behavior |
|---|---|
| Invalid request body | `400` |
| Unknown notification id | `404` |
| Broker publish failure after DB insert | Mark `FAILED` with error (or leave `PENDING` and document retry — prefer mark `FAILED` with clear message for MVP honesty) |
| Email send transient failure | Retry up to max attempts |
| Email send exhausted | DLQ + status `FAILED` |
| Duplicate delivery | Idempotent no-op if already `SENT` |

## Testing Strategy

- **Unit:** validation, status transitions, idempotent listener logic with mocked `EmailSender` / repository.
- **Integration:** Testcontainers Postgres (+ RabbitMQ if stable in CI); create notification → assert eventual `SENT` with logging sender.
- **Failure path:** stub `EmailSender` to throw → assert retries then `FAILED` / DLQ.

## Local Run (target)

```bash
docker compose up -d    # Postgres + RabbitMQ
mvn spring-boot:run
curl -s -X POST http://localhost:8080/notifications \
  -H 'Content-Type: application/json' \
  -d '{"to":"a@b.com","subject":"Hi","body":"Hello"}'
```

## Success Criteria

1. Docker Compose starts Postgres and RabbitMQ.
2. `POST /notifications` returns `202` with `PENDING`.
3. Worker processes message; `GET` shows `SENT` when using logging sender.
4. Forced send failures exhaust retries, land on DLQ, status `FAILED`.
5. Re-delivery of an already `SENT` id does not double-send (idempotent).
6. README documents API, config switches (`logging` vs `smtp`), and demo commands.
7. Tests pass without requiring a real SMTP server.

## Future Work (interview talking points)

1. Split API and worker into two deployables sharing the same queue.
2. Transactional outbox for publish reliability.
3. Auth + per-user notification history.
4. Templates, attachments, provider webhooks (SendGrid/SES).
5. Metrics: queue depth, send latency, failure rate.
6. Priority / delayed messages.
