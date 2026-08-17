CREATE TABLE notification (
    id          UUID PRIMARY KEY,
    to_email    TEXT        NOT NULL,
    subject     TEXT        NOT NULL,
    body        TEXT        NOT NULL,
    status      TEXT        NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    sent_at     TIMESTAMPTZ
);

CREATE INDEX idx_notification_status ON notification (status);
