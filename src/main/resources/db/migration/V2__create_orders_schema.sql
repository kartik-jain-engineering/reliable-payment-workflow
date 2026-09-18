-- Order module schema.
--
-- Flyway is the source of truth for the schema; R2DBC has no schema-validation
-- equivalent to JPA's ddl-auto, so this DDL must stay in lockstep with
-- OrderRow / OrderItemRow by hand, backed by the repository integration test.
-- Identifiers are assigned by the domain, hence no DB-side default on `id`.

CREATE TABLE orders (
    id           uuid           NOT NULL,
    customer_id  varchar(128)   NOT NULL,
    currency     varchar(3)     NOT NULL,
    total_amount numeric(19, 2) NOT NULL,
    status       varchar(32)    NOT NULL,
    version      bigint         NOT NULL,
    created_at   timestamptz    NOT NULL,
    updated_at   timestamptz    NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (id)
);

CREATE TABLE order_items (
    id         uuid           NOT NULL,
    order_id   uuid           NOT NULL,
    product_id varchar(128)   NOT NULL,
    quantity   integer        NOT NULL,
    unit_price numeric(19, 2) NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
