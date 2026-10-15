CREATE TABLE notification_outbox (
    id              UUID PRIMARY KEY,
    notification_id UUID        NOT NULL REFERENCES notification (id),
    created_at      TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON notification_outbox (created_at)
    WHERE published_at IS NULL;
