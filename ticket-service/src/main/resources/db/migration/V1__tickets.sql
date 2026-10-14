CREATE TABLE tickets (
    id              UUID PRIMARY KEY,
    customer_email  VARCHAR(320)  NOT NULL,
    subject         VARCHAR(200)  NOT NULL,
    body            TEXT          NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    category        VARCHAR(30),
    priority        VARCHAR(10),
    -- priority as a number, so the queue can be ordered in SQL
    priority_rank   INT           NOT NULL DEFAULT -1,
    language        VARCHAR(10),
    sentiment       VARCHAR(10),
    summary         TEXT,
    triage_source   VARCHAR(10),
    draft_reply     TEXT,
    citations       TEXT,
    final_reply     TEXT,
    created_at      TIMESTAMPTZ   NOT NULL,
    triaged_at      TIMESTAMPTZ,
    resolved_at     TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX tickets_queue ON tickets (status, priority_rank DESC, created_at);
