-- Idempotency module schema.
--
-- Same hand-maintained contract as V2/V3: this table must stay in lockstep with
-- IdempotencyKeyEntity. The unique constraint on (owner_id, idempotency_key) is
-- what decides which of several concurrent requests claims a key, so keys are
-- scoped per authenticated subject and never shared across customers.

CREATE TABLE idempotency_keys (
    id               uuid         NOT NULL,
    owner_id         varchar(255) NOT NULL,
    idempotency_key  varchar(255) NOT NULL,
    request_hash     varchar(64)  NOT NULL,
    status           varchar(32)  NOT NULL,
    response_status  integer,
    response_headers text,
    response_body    text,
    version          bigint       NOT NULL,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_keys_owner_key UNIQUE (owner_id, idempotency_key)
);
