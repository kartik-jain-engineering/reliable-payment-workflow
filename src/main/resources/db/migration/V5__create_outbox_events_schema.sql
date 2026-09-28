-- Outbox module schema.
--
-- Same hand-maintained contract as V2-V4: these tables must stay in lockstep with
-- OutboxEventEntity, OutboxConsumedEventEntity and OutboxEventReceiptEntity.
-- FAILED outbox rows are kept for inspection and never deleted.

CREATE TABLE outbox_events (
    id             uuid          NOT NULL,
    aggregate_type varchar(64)   NOT NULL,
    aggregate_id   uuid          NOT NULL,
    event_type     varchar(128)  NOT NULL,
    event_version  integer       NOT NULL,
    payload        text          NOT NULL,
    status         varchar(16)   NOT NULL,
    attempts       integer       NOT NULL,
    last_error     varchar(1000),
    version        bigint        NOT NULL,
    created_at     timestamptz   NOT NULL,
    published_at   timestamptz,
    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_outbox_events_status_created_at ON outbox_events (status, created_at);

-- The unique constraint on (handler_name, event_id) is what decides whether a handler
-- has already consumed an event, so redelivery never repeats its business effect.
CREATE TABLE outbox_consumed_events (
    id           uuid         NOT NULL,
    handler_name varchar(128) NOT NULL,
    event_id     uuid         NOT NULL,
    consumed_at  timestamptz  NOT NULL,
    CONSTRAINT pk_outbox_consumed_events PRIMARY KEY (id),
    CONSTRAINT uq_outbox_consumed_events_handler_event UNIQUE (handler_name, event_id),
    CONSTRAINT fk_outbox_consumed_events_event FOREIGN KEY (event_id) REFERENCES outbox_events (id)
);

-- Deliberately no unique constraint here: duplicate receipts must be prevented by the
-- consumer claim above, not masked by this table.
CREATE TABLE outbox_event_receipts (
    id           uuid         NOT NULL,
    handler_name varchar(128) NOT NULL,
    event_id     uuid         NOT NULL,
    event_type   varchar(128) NOT NULL,
    aggregate_id uuid         NOT NULL,
    received_at  timestamptz  NOT NULL,
    CONSTRAINT pk_outbox_event_receipts PRIMARY KEY (id)
);

CREATE INDEX idx_outbox_event_receipts_event_id ON outbox_event_receipts (event_id);
