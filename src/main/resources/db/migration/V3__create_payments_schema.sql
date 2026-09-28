-- Payment module schema.
--
-- Same hand-maintained contract as V2: Flyway owns the DDL, R2DBC does not validate
-- it, so this table must stay in lockstep with PaymentEntity, backed by the
-- repository integration test. Identifiers are assigned by the domain, hence no
-- DB-side default on `id`.

CREATE TABLE payments (
    id         uuid           NOT NULL,
    order_id   uuid           NOT NULL,
    amount     numeric(19, 2) NOT NULL,
    currency   varchar(3)     NOT NULL,
    status     varchar(32)    NOT NULL,
    version    bigint         NOT NULL,
    created_at timestamptz    NOT NULL,
    updated_at timestamptz    NOT NULL,
    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE INDEX idx_payments_order_id ON payments (order_id);
