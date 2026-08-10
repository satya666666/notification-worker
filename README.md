# Notification Worker

Async notification service (Java 21 / Spring Boot 3): accept an email job over HTTP, enqueue on **RabbitMQ**, process with retries + **DLQ**, track status in **Postgres**.

Email delivery goes through an `EmailSender` interface — **logging** by default, optional **SMTP**.

## Status

Design approved. Implementation not started yet.

See: [docs/superpowers/specs/2026-08-10-notification-worker-design.md](docs/superpowers/specs/2026-08-10-notification-worker-design.md)

## Planned stack

Java 21 · Spring Boot 3 · PostgreSQL · RabbitMQ · Docker Compose · Testcontainers

## Planned API

- `POST /notifications` → `{ id, status: "PENDING" }`
- `GET /notifications/{id}` → job status (`PENDING` | `PROCESSING` | `SENT` | `FAILED`)
