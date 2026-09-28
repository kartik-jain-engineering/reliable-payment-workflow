# ledgerflow

A modular monolith for processing payment workflows reliably (idempotent
requests, transactional outbox for events, auditable ledger postings).

**Status:** the `order` module has a working create / fetch / cancel API
backed by PostgreSQL, and is currently the only module with any code. This
iteration also establishes the project structure, build, config, database
migration pipeline, and test setup that future modules (`payment`, `ledger`,
`idempotency`, `outbox`, ...) will build on, following the same
`api` / `service` / `repo` / `model` package layout as `order`.

## Stack

- Java 21
- Spring Boot 3.3 (WebFlux, Data R2DBC, Actuator, Validation)
- Lombok, for boilerplate-free entities and constructor injection
- Gradle (wrapper committed — no local Gradle install required)
- PostgreSQL, accessed over R2DBC by the app and over blocking JDBC by Flyway
- JUnit 5, Reactor Test, Testcontainers, ArchUnit

## Module layout

Single deployable, package-per-module inside `com.ledgerflow`. `order` is
currently the only module with real code:

```
com.ledgerflow
└── order
    ├── api      REST controllers, the exception handler, and DTO <-> entity mapping
    ├── service  use cases (OrderService): orchestrates repo calls, re-validates before writing
    ├── repo     Spring Data ReactiveCrudRepository interfaces
    └── model
        ├── (OrderStatus, InvalidStateTransitionException)
        ├── entity      Spring Data R2DBC entities (OrderEntity, OrderItemEntity)
        ├── dto         API request/response records (CreateOrderRequest, OrderResponse, ...)
        └── constraint  shared Bean Validation constraints (@ValidCurrencyCode)
```

Dependencies point inward: `api` → `service` → `repo`, all depending on
`model`; `model` never depends back out on any of the other three. This is
enforced by the ArchUnit rules in `src/test/java/.../architecture`, which
also keep the top-level module packages (currently just `order`) free of
cycles — new modules (`payment`, `ledger`, `idempotency`, `outbox`, `common`)
should follow the same four-package layout once they have real code.

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
and `OrderServiceIntegrationTest`. Contributors without Docker
available can run everything else — the entity and validation unit tests,
the `@WebFluxTest` controller slice, and the ArchUnit module-boundary
checks — by targeting those packages instead:

```powershell
.\gradlew.bat test --tests "com.ledgerflow.order.model.*" --tests "com.ledgerflow.order.api.*" --tests "com.ledgerflow.order.service.OrderServiceTest" --tests "*Architecture*" --console=plain
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

Valid transitions, enforced by `OrderEntity.transitionTo` on the entity itself
(not in the service or the controller):

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
  end to end — controller, service and repositories all return
  `Mono`/`Flux` — so the module composes cleanly with the non-blocking
  payment/ledger workflows planned for later iterations.
- **`api` / `service` / `repo` / `model` (entity + dto), not a DDD-style
  `domain` / `application` / `infrastructure` split.** Earlier iterations of
  this module kept a framework-free domain aggregate (`Order`) separate from
  its persistence row (`OrderRow`), translated between them by a repository
  adapter. That split added a mapping layer for no real benefit at this
  module's current size, so `Order`/`OrderRow` were merged into one
  Lombok-backed, Spring Data-mapped, Bean Validation-annotated
  `OrderEntity` (same for `OrderItem`/`OrderItemRow` → `OrderItemEntity`),
  and business rules (the lifecycle state machine) live directly on it.
- **Lombok generates the boilerplate on entities and the service/controller
  constructors.** `OrderEntity` / `OrderItemEntity` use `@Getter`, a
  selective `@Setter` (`items` is excluded, replaced by a hand-written
  defensive-copy setter — see below), `@Builder` and
  `@NoArgsConstructor`/`@AllArgsConstructor` for the Spring Data-required
  no-args constructor; `OrderService` and `OrderController` use
  `@RequiredArgsConstructor` instead of a hand-written constructor.
  `model.dto` request/response types stay Java records rather than Lombok
  classes — records already give immutability and generated
  accessors/`equals`/`hashCode` for free, so wrapping them in Lombok would
  add annotations without removing any boilerplate.
- **Bean Validation on both the DTO and the entity, not hand-written
  `if (...) throw new IllegalArgumentException(...)` checks.** The old
  `Order`/`OrderItem` had a private `Preconditions` helper re-implementing
  the same rules `CreateOrderRequest` already enforced at the API boundary.
  That helper is gone: `OrderEntity` / `OrderItemEntity` carry the same
  `@NotBlank` / `@NotNull` / `@DecimalMin` / `@ValidCurrencyCode` /
  `@Positive` / `@PositiveOrZero` constraints as their DTO counterparts
  (`items` is `List<@NotNull @Valid OrderItemEntity>`, a container-element
  constraint that rejects both a missing list and a null entry inside it),
  and `OrderService` injects a `jakarta.validation.Validator` and validates
  every entity immediately before writing it — defence in depth for any
  caller that builds an `OrderEntity` without going through the controller,
  using the same mechanism rather than a second, imperative one.
- **`OrderEntity implements Persistable<UUID>`, so `ReactiveCrudRepository
  .save()` can be used directly.** Order and order-item ids are assigned by
  the application (`OrderEntity.createNew`) before a row ever reaches the
  database. Spring Data R2DBC's default insert/update heuristic for a
  `@Version`-annotated entity treats a non-null version (even the seeded
  `0`) as "not new", so a plain `save()` on a brand-new order would attempt
  an UPDATE matching zero rows and silently drop it. Implementing
  `Persistable` replaces that heuristic with an explicit `@Transient boolean
  isNew` flag: `createNew` sets it `true`; rows Spring Data materialises
  from the database leave it at its default (`false`), since `@Transient`
  fields are never populated from a column. `@Version` still drives
  optimistic locking on every update.
- **Reactive `@Transactional` on aggregate writes.** `OrderService.save`
  is transactional so the order row write and the line-item replace happen
  in one reactive transaction.
- **Persisting an order is an explicit multi-statement write, not a cascaded
  `save()`.** Spring Data R2DBC has no aggregate/one-to-many mechanism
  equivalent to Spring Data JDBC's `@MappedCollection` — there is no way to
  declare that `OrderEntity` owns its `OrderItemEntity`s and have the
  framework cascade the write. `OrderService.save` therefore saves the
  `orders` row via `OrderRepository`, then deletes and re-inserts the
  order's `order_items` rows wholesale via `OrderItemRepository`, forcing
  every re-inserted item's `isNew` flag back to `true` (the delete means any
  subsequent write for the same id must be an insert, never an update).
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
  R2DBC has no equivalent, so a mismatch between `OrderEntity`/`OrderItemEntity`
  and the Flyway-managed schema would only surface at query time. The
  compensating control is `OrderServiceIntegrationTest`, a
  Testcontainers-backed round trip through every column of both tables.
- **`totalAmount` is client-supplied, not derived from `items`.** In this
  iteration `CreateOrderRequest.totalAmount()` is taken as given and is not
  cross-checked against the sum of the line items — a deliberate but
  unusual-looking choice for this stage. A later iteration would likely
  compute it from `items` and reject a request where the two disagree.
- **Currency codes are validated against the JDK's currency registry, not a
  regex.** The `@ValidCurrencyCode` Bean Validation constraint
  (`CurrencyCodeValidator`, in `model.constraint` so both `model.dto` and
  `model.entity` can use it) calls `java.util.Currency.getInstance(...)` and
  treats an `IllegalArgumentException` as "invalid", rather than matching
  `[A-Z]{3}`, because a regex would accept well-formed but non-existent
  codes such as `ZZZ`.
- **`status` is stored as a plain `String` column, not the `OrderStatus`
  enum directly.** R2DBC's Postgres driver reads a varchar into an enum out
  of the box but will not write one back without a registered converter.
  `OrderEntity` keeps the raw value in a `statusCode` field (mapped to the
  `status` column) and exposes a typed `getStatus()`/`setStatus(OrderStatus)`
  pair, so every other layer still works with the enum.
- **The application assigns order and order-item ids, not the database.**
  `OrderEntity.createNew` calls `UUID.randomUUID()` for the order and stamps
  a fresh id onto each item. The `id` columns in `orders` and `order_items`
  are native PostgreSQL `uuid` with no `DEFAULT` — the entity owns its
  identity from the moment it exists, not from the moment it is persisted.
- **No MapStruct.** `OrderMapper` is a small hand-written, stateless utility
  class translating between `model.dto` and `model.entity` types.
- **Errors are RFC 7807 `ProblemDetail`**, not a bespoke error DTO, so every
  handler in `OrderExceptionHandler` produces the same body shape —
  including `ConstraintViolationException` from `OrderService`'s
  persistence-boundary validation, mapped to the same 400 "Invalid order"
  response as the legacy `IllegalArgumentException` handler.
- **Line items live in a separate `order_items` table** (`V2__create_orders_schema.sql`),
  one row per item, foreign-keyed to `orders`, rather than being inlined
  into the `orders` row. `OrderEntity.items` is `@Transient`: Spring Data
  ignores it for the `orders` row mapping, and `OrderService` populates it
  from a separate `OrderItemRepository` query.
- **Money is `BigDecimal` end to end** (`totalAmount`, `unitPrice`), backed
  by `numeric(19, 2)` columns — no `double`/`float` anywhere in the order
  model.

## Explicitly out of scope for this iteration

- Kafka, Redis, Keycloak, Kubernetes, and any frontend — not added yet.
- Actual payment processing: no payment gateway integration and no
  charge/capture logic. `PAYMENT_PENDING`, `CONFIRMED` and `FAILED` exist as
  `OrderStatus` values and their transition rules are enforced on the
  entity, but nothing in this codebase drives an order into them — the
  only order transition currently reachable through the API is `cancel`.
- The `payment`, `ledger`, `idempotency`, `outbox` and `common` module
  packages: removed rather than kept as empty scaffolding, since they had
  no code. Re-add them with the same `api`/`service`/`repo`/`model` layout
  as `order` once they have real behaviour.
