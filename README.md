# Notification Worker

Async notification service (Java 21 / Spring Boot 3): accept an email job over HTTP, enqueue on **RabbitMQ**, process with retries + **DLQ**, track status in **Postgres**.

Email delivery goes through an `EmailSender` interface — **logging** by default, optional **SMTP**.

## Stack

Java 21 · Spring Boot 3.4 · PostgreSQL · RabbitMQ · Docker Compose · Testcontainers

## Quick start

Requires **JDK 21** (not 25+) and Docker Desktop running.

> Postgres is mapped to host port **5433** (avoids clashing with a local Postgres on 5432).

```bash
# Dependencies
docker compose up -d

# Run the app
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

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/notifications` | Create job → `202` `{ id, status }` |
| `GET` | `/notifications/{id}` | Job status → `200` or `404` |

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
- DLQ: `notifications.send.dlq`
- Payload: `{ "notificationId": "<uuid>" }` (DB is source of truth for content)

On send failure the worker increments `attempts`, requeues while under max, then marks `FAILED` and rejects to the DLQ. Already `SENT` / `FAILED` messages are no-ops (idempotent).

RabbitMQ management UI: http://localhost:15672 (guest/guest)

## Tests

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn test
```

- Unit tests: listener transitions, publish failure handling (mocked)
- Integration tests: Testcontainers Postgres + RabbitMQ (happy path, retries/DLQ, idempotency, validation)

> Docker Engine 29+ needs API version ≥ 1.41. The Surefire config sets `api.version=1.44` for Testcontainers.

## Docs

- **Full project guide:** [docs/PROJECT-OVERVIEW.md](docs/PROJECT-OVERVIEW.md)
- Design: [docs/superpowers/specs/2026-08-10-notification-worker-design.md](docs/superpowers/specs/2026-08-10-notification-worker-design.md)
- Implementation plan: [docs/superpowers/plans/2026-08-10-notification-worker.md](docs/superpowers/plans/2026-08-10-notification-worker.md)
