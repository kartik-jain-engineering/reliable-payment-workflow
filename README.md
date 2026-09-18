# ledgerflow

A modular monolith for processing payment workflows reliably (idempotent
requests, transactional outbox for events, auditable ledger postings).

**Status:** the `order` module has a working create / fetch / cancel API
backed by PostgreSQL; `common`, `payment`, `ledger`, `idempotency` and
`outbox` are still empty scaffolding. This iteration also establishes the
project structure, build, config, database migration pipeline, and test
setup that those other modules will build on.

## Stack

- Java 21
- Spring Boot 3.3 (WebFlux, Data R2DBC, Actuator)
- Gradle (wrapper committed — no local Gradle install required)
- PostgreSQL, accessed over R2DBC by the app and over blocking JDBC by Flyway
- JUnit 5, Reactor Test, Testcontainers, ArchUnit

## Module layout

Single deployable, package-per-module inside `com.ledgerflow`:

```
com.ledgerflow
├── common          shared kernel (types every module may depend on)
├── payment         core payment workflow module
├── ledger          balance / posting module
├── idempotency     duplicate-request detection module
├── order           order lifecycle module (api / application / domain / infrastructure)
└── outbox          transactional outbox for reliable event publishing
```

Each module is layered as `domain` → `application` → `infrastructure`
(dependencies point inward). Modules are kept in one Maven-style build
rather than separate build modules for simplicity; boundaries are enforced
by the ArchUnit test in `src/test/java/.../architecture` instead, and
tightened as real code lands in each module.

`order` is the first module with real code, and adds an `api` layer on top
of the usual three for its REST controller and request/response DTOs
(`api` → `application` → `domain` ← `infrastructure`, dependencies still
pointing inward). `common`, `payment`, `ledger`, `idempotency` and `outbox`
remain empty `package-info.java` skeletons.

## Running locally

Start PostgreSQL:

```bash
docker compose up -d
```

Run the app against it:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

Health check: http://localhost:8080/actuator/health

## Building and testing

```bash
./gradlew clean build
```

This compiles, runs the unit/architecture tests, and runs
`LedgerflowApplicationTests`, which boots the full Spring context
against a disposable PostgreSQL container via Testcontainers — Docker must
be running locally for this test (it runs automatically in CI).

On Windows, use `.\gradlew.bat` instead of `./gradlew` for all of the
commands in this README.

### Running tests without Docker

Two test classes require Docker Desktop to be running, since they start a
real PostgreSQL container via Testcontainers: `LedgerflowApplicationTests`
and `OrderRepositoryAdapterIntegrationTest`. Contributors without Docker
available can run everything else — the domain and validation unit tests,
the `@WebFluxTest` controller slice, and the ArchUnit module-boundary
checks — by targeting those packages instead:

```powershell
.\gradlew.bat test --tests "com.ledgerflow.order.*" --tests "*Architecture*" --console=plain
```

## Configuration profiles

| File                      | Purpose                                            |
|---------------------------|-----------------------------------------------------|
| `application.yml`         | Base config shared by all profiles                  |
| `application-local.yml`   | Local dev; sets both the app's `spring.r2dbc.*` URL and Flyway's separate `spring.flyway.*` JDBC URL against `docker-compose` PostgreSQL |
| `application-test.yml`    | Test profile; both the R2DBC and JDBC connection details are supplied by Testcontainers via `@ServiceConnection` |

## Database migrations

Flyway migrations live in `src/main/resources/db/migration`, named
`V<version>__description.sql`. `V1__initial_schema.sql` is currently a
placeholder — the first real domain migration should be added as
`V2__....sql`.

## Order API

The `order` module exposes three endpoints under `/api/v1/orders`. Errors
are returned as RFC 7807 `ProblemDetail` bodies (see below).

### Create an order

`POST /api/v1/orders` → `201 Created`, with a `Location` header pointing at
the new order.

```bash
curl -i -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-123",
    "currency": "USD",
    "totalAmount": 59.97,
    "items": [
      { "productId": "sku-1", "quantity": 2, "unitPrice": 19.99 },
      { "productId": "sku-2", "quantity": 1, "unitPrice": 19.99 }
    ]
  }'
```

Response (`201`, `Location: /api/v1/orders/b6f1c8b0-6e0e-4b8a-9c2c-2c7a9b1f0a11`):

```json
{
  "id": "b6f1c8b0-6e0e-4b8a-9c2c-2c7a9b1f0a11",
  "customerId": "cust-123",
  "currency": "USD",
  "totalAmount": 59.97,
  "status": "CREATED",
  "items": [
    { "productId": "sku-1", "quantity": 2, "unitPrice": 19.99 },
    { "productId": "sku-2", "quantity": 1, "unitPrice": 19.99 }
  ],
  "createdAt": "2026-09-14T10:15:30Z",
  "updatedAt": "2026-09-14T10:15:30Z"
}
```

### Fetch an order

`GET /api/v1/orders/{orderId}` → `200 OK` with the same body shape as above.

```bash
curl http://localhost:8080/api/v1/orders/b6f1c8b0-6e0e-4b8a-9c2c-2c7a9b1f0a11
```

### Cancel an order

`POST /api/v1/orders/{orderId}/cancel` → `200 OK` with the same body shape,
`status` now `CANCELLED`. This is currently the only state transition
exposed over the API — see [Order state machine](#order-state-machine).

```bash
curl -X POST http://localhost:8080/api/v1/orders/b6f1c8b0-6e0e-4b8a-9c2c-2c7a9b1f0a11/cancel
```

### Error responses

All three endpoints share one exception handler
(`OrderExceptionHandler`, scoped to `com.ledgerflow.order`), which always
returns a Spring `ProblemDetail`.

`400 Bad Request` — request validation failure (`CreateOrderRequest`'s Bean
Validation constraints), body includes an `errors` array of
`{ "field", "message" }`:

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "Request validation failed",
  "errors": [
    { "field": "currency", "message": "must be a valid ISO 4217 currency code" },
    { "field": "items", "message": "must not be empty" }
  ]
}
```

`404 Not Found` — no order exists with the given id:

```json
{
  "type": "about:blank",
  "title": "Order not found",
  "status": 404,
  "detail": "order not found: 7c2e0c2a-1111-4c1a-9a2e-000000000000"
}
```

`409 Conflict` — the requested transition is not legal from the order's
current state (e.g. cancelling an order that is already `CONFIRMED`):

```json
{
  "type": "about:blank",
  "title": "Invalid order state transition",
  "status": 409,
  "detail": "cannot transition order from CONFIRMED to CANCELLED"
}
```

## Order state machine

An order moves through five states (`OrderStatus`): `CREATED`,
`PAYMENT_PENDING`, `CONFIRMED`, `CANCELLED`, `FAILED`. `CONFIRMED`,
`CANCELLED` and `FAILED` are terminal.

Valid transitions, enforced by `Order.transitionTo` on the aggregate itself
(not in the application service or the controller):

- `CREATED` → `PAYMENT_PENDING`
- `CREATED` → `CANCELLED`
- `PAYMENT_PENDING` → `CONFIRMED`
- `PAYMENT_PENDING` → `FAILED`
- `PAYMENT_PENDING` → `CANCELLED`

Any other transition — including moving out of `CONFIRMED`, `CANCELLED` or
`FAILED`, and any self-transition — throws
`InvalidStateTransitionException`, which `OrderExceptionHandler` maps to
`409 Conflict`. Only `cancel` is currently exposed via the API; nothing
drives an order into `PAYMENT_PENDING`, `CONFIRMED` or `FAILED` yet.

## Design decisions

- **WebFlux + R2DBC, not MVC + JPA.** The `order` module is built reactively
  end to end — controller, application service and repository adapter all
  return `Mono`/`Flux` — so the module composes cleanly with the
  non-blocking payment/ledger workflows planned for later iterations.
- **Persisting an order is an explicit multi-statement write, not a cascaded
  `save()`.** Spring Data R2DBC has no aggregate/one-to-many mechanism
  equivalent to Spring Data JDBC's `@MappedCollection` — there is no way to
  declare that `Order` owns its `OrderItem`s and have the framework cascade
  the write. `OrderRepositoryAdapter.save` therefore writes the `orders` row
  itself, then deletes and re-inserts the order's `order_items` rows
  wholesale via `R2dbcEntityTemplate`, rather than diffing individual items.
- **Insert vs. update is decided explicitly, not inferred from `save()`.**
  Order and order-item ids are assigned by the domain
  (`UUID.randomUUID()`) before the row ever reaches the database, so a plain
  `template.save(row)` on a brand-new order would issue an UPDATE matching
  zero rows and silently drop it. `OrderRepositoryAdapter.save` does a
  `selectOne` by id first, then calls `template.update(...)` on a hit or
  `template.insert(...)` on a miss.
- **`TransactionalOperator`, not `@Transactional`.** `@Transactional` is
  built for blocking call stacks and does not compose with `Mono`/`Flux`
  pipelines. `OrderRepositoryAdapter` builds a `TransactionalOperator` by
  hand from the auto-configured `ReactiveTransactionManager` (Boot
  auto-configures the manager but not the operator) and wraps it around
  `.save()` with `.as(transactionalOperator::transactional)`, at the point
  where the order row and its items are written together, since that is the
  only place more than one statement is issued.
- **Flyway still runs over blocking JDBC.** Reactive Spring never derives a
  JDBC `DataSource` from `spring.r2dbc.*`, so Flyway needs its own
  connection details — `application-local.yml` sets `spring.flyway.url` /
  `.user` / `.password` explicitly, and the test profile gets both the
  R2DBC and JDBC details from the same Testcontainers instance via
  `@ServiceConnection`. This is also why `org.postgresql:postgresql` (the
  blocking JDBC driver) stays on the runtime classpath alongside
  `org.postgresql:r2dbc-postgresql` — Flyway needs it even though the
  application itself never opens a JDBC connection.
- **No `ddl-auto: validate` safety net.** Under JPA, Hibernate could compare
  the entity mapping to the live schema at startup and fail fast on drift;
  R2DBC has no equivalent, so a mismatch between `OrderRow`/`OrderItemRow`
  and the Flyway-managed schema would only surface at query time. The
  compensating control is `OrderRepositoryAdapterIntegrationTest`, a
  Testcontainers-backed round trip through every column of both tables.
- **`totalAmount` is client-supplied, not derived from `items`.** In this
  iteration `CreateOrderRequest.totalAmount()` is taken as given and is not
  cross-checked against the sum of the line items — a deliberate but
  unusual-looking choice for this stage. A later iteration would likely
  compute it from `items` and reject a request where the two disagree.
- **Currency codes are validated against the JDK's currency registry, not a
  regex.** Both the `@ValidCurrencyCode` Bean Validation constraint
  (`CurrencyCodeValidator`) at the API boundary and `Order.validateCurrency`
  in the domain call `java.util.Currency.getInstance(...)` and reject an
  `IllegalArgumentException`, rather than matching `[A-Z]{3}`, because a
  regex would accept well-formed but non-existent codes such as `ZZZ`.
- **The domain assigns order and order-item ids, not the database.**
  `Order.create` calls `UUID.randomUUID()` when the aggregate is
  constructed, and `OrderItemRow.forInsert` does the same for its surrogate
  key. The `id` columns in `orders` and `order_items` are native PostgreSQL
  `uuid` with no `DEFAULT` — the aggregate owns its identity from the moment
  it exists, not from the moment it is persisted.
- **Optimistic locking is a persistence-only concern.** `@Version` is
  declared on `OrderRow` and backed by the `version` column; the `Order`
  aggregate itself has no version field, so concurrency control cannot leak
  into domain logic or the `OrderRepository` port.
- **No MapStruct.** `OrderMapper` is a small hand-written, stateless utility
  class translating between the REST DTOs and the domain types.
- **Persistence row types are package-private.** `OrderRow` and
  `OrderItemRow` cannot be referenced outside
  `com.ledgerflow.order.infrastructure`; every other layer works with the
  `Order` / `OrderItem` domain types.
- **Errors are RFC 7807 `ProblemDetail`**, not a bespoke error DTO, so every
  handler in `OrderExceptionHandler` produces the same body shape.
- **Line items live in a separate `order_items` table** (`V2__create_orders_schema.sql`),
  one row per item, foreign-keyed to `orders`, rather than being inlined
  into the `orders` row.
- **Money is `BigDecimal` end to end** (`totalAmount`, `unitPrice`), backed
  by `numeric(19, 2)` columns — no `double`/`float` anywhere in the order
  model.

## Explicitly out of scope for this iteration

- Kafka, Redis, Keycloak, Kubernetes, and any frontend — not added yet.
- Actual payment processing: no payment gateway integration and no
  charge/capture logic. `PAYMENT_PENDING`, `CONFIRMED` and `FAILED` exist as
  `OrderStatus` values and their transition rules are enforced on the
  aggregate, but nothing in this codebase drives an order into them — the
  only order transition currently reachable through the API is `cancel`.
