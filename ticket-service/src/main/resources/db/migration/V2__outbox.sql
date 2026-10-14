-- Transactional outbox: events are written in the same transaction as the ticket,
-- then relayed to Kafka, so a Kafka outage can delay events but never lose them.
CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY,
    topic         VARCHAR(100) NOT NULL,
    message_key   VARCHAR(100) NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    published_at  TIMESTAMPTZ
);

CREATE INDEX outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
