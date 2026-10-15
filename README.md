# Notification Worker

Async notification service (Java 21 / Spring Boot 3): accept an email job over HTTP, write it to an **outbox** in Postgres, publish to **RabbitMQ**, process with **delayed retries** + **DLQ**, track status in **Postgres**.

API and worker are the same JAR, split by role. Email goes through an `EmailSender` interface — **logging** by default, optional **SMTP**.

## Stack

Java 21 · Spring Boot 3.4 · PostgreSQL · RabbitMQ · Docker Compose · Testcontainers · Actuator

## Quick start

Requires **JDK 21** (not 25+) and Docker Desktop running.

> Postgres is mapped to host port **5433** (avoids clashing with a local Postgres on 5432).

### Full stack (API + worker containers)

```bash
docker compose up --build
```

API listens on **8080**; worker health/metrics on **8081**.

### Local Maven (single process, both roles)

```bash
docker compose up -d postgres rabbitmq
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn spring-boot:run
```

### Create a notification

```bash
curl -s -X POST http://localhost:8080/notifications \
  -H 'Content-Type: application/json' \
  -d '{"to":"a@b.com","subject":"Hi","body":"Hello"}'
```

Response `202 Accepted`:

```json
{ "id": "<uuid>", "status": "PENDING" }
```

### Check status

```bash
curl -s http://localhost:8080/notifications/<uuid>
```

When using the default logging provider, status becomes `SENT` shortly after enqueue.

Health: `GET http://localhost:8080/actuator/health`

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/notifications` | Create job → `202` `{ id, status }` |
| `GET` | `/notifications/{id}` | Job status → `200` or `404` |
| `GET` | `/actuator/health` | Liveness |
| `GET` | `/actuator/metrics` | Micrometer counters (`notifications.*`) |

Request body for create:

```json
{ "to": "user@example.com", "subject": "...", "body": "..." }
```

Statuses: `PENDING` → `PROCESSING` → `SENT` | `FAILED`

## Configuration

Key settings in `src/main/resources/application.yml` (overridable via env):

| Property / env | Default | Notes |
|---|---|---|
| `notification.max-attempts` / `NOTIFICATION_MAX_ATTEMPTS` | `3` | Retries before DLQ + `FAILED` |
| `notification.retry.initial-backoff-ms` | `1000` | First retry delay |
| `notification.retry.multiplier` | `2.0` | Exponential backoff |
| `notification.roles.api` / `NOTIFICATION_ROLE_API` | `true` | REST + outbox publisher |
| `notification.roles.worker` / `NOTIFICATION_ROLE_WORKER` | `true` | Queue listener |
| `notification.email.provider` / `NOTIFICATION_EMAIL_PROVIDER` | `logging` | `logging` or `smtp` |
| `notification.email.from` / `NOTIFICATION_EMAIL_FROM` | `noreply@example.com` | SMTP From header |
| `DB_*` / `RABBITMQ_*` | localhost defaults | Compose uses Postgres **5433**, RabbitMQ 5672 |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD` | — | Required when provider=`smtp` |

### Switch to SMTP

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

## Messaging

- Exchange: `notifications` (direct)
- Queue: `notifications.send` (DLX → `notifications.dlx`)
- Retry: `notifications.send.retry` (per-message TTL, then back to `send`)
- DLQ: `notifications.send.dlq`
- Payload: `{ "notificationId": "<uuid>" }` (DB is source of truth for content)

`POST` writes the row and an **outbox** event in one transaction. A poller publishes after commit. On send failure the worker increments `attempts`, republishes to the retry queue with backoff, then marks `FAILED` and rejects to the DLQ. Already `SENT` / `FAILED` messages are no-ops (idempotent).

RabbitMQ management UI: http://localhost:15672 (guest/guest)

## Tests

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn test
```

- Unit tests: listener transitions, outbox publish, backoff, create path (mocked)
- Integration tests: Testcontainers Postgres + RabbitMQ (happy path, retries/DLQ, idempotency, validation, health)

> Docker Engine 29+ needs API version ≥ 1.41. The Surefire config sets `api.version=1.44` for Testcontainers.

CI runs the same suite on every push/PR (`.github/workflows/ci.yml`).

## Docs

- **Full project guide:** [docs/PROJECT-OVERVIEW.md](docs/PROJECT-OVERVIEW.md)
- Design: [docs/superpowers/specs/2026-08-10-notification-worker-design.md](docs/superpowers/specs/2026-08-10-notification-worker-design.md)
- Implementation plan: [docs/superpowers/plans/2026-08-10-notification-worker.md](docs/superpowers/plans/2026-08-10-notification-worker.md)
