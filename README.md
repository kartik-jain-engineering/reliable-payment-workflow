# ledgerflow

A modular monolith for processing payment workflows reliably (idempotent
requests, transactional outbox for events, auditable ledger postings).

**Status:** the `order` module has a working create / fetch / cancel API,
and the `payment` module can authorize a payment for an order that is
already `PAYMENT_PENDING`, driving the order to `CONFIRMED` on success or
`FAILED` on decline/timeout — both backed by PostgreSQL. Every order
creation and payment settlement also appends a domain event to the
`outbox` module in the same database transaction as the business write —
see [Events and reliable publication](#events-and-reliable-publication).
Every `order` and `payment` endpoint requires a JWT bearer token and a
per-route OAuth2 scope, plus a resource-ownership check tying orders and
payments to the JWT subject — see [Trust boundaries](#trust-boundaries).
`POST /api/v1/payments` also requires an `Idempotency-Key` header, handled
by the `idempotency` module — see [Idempotency](#idempotency). `ledger` is
not built yet; it will follow the same `api` / `service` / `repo` /
`model` package layout as `order`, `payment` and `idempotency` once it has
real code.

## Stack

- Java 21
- Spring Boot 3.3 (WebFlux, Data R2DBC, Actuator, Validation, OAuth2 Resource Server)
- Lombok, for boilerplate-free entities and constructor injection
- Gradle (wrapper committed — no local Gradle install required)
- PostgreSQL, accessed over R2DBC by the app and over blocking JDBC by Flyway
- Keycloak, as the local JWT issuer for the OAuth2 resource server (dev only — see `docker-compose.yml`)
- JUnit 5, Reactor Test, Testcontainers, ArchUnit, spring-security-test

## Module layout

Single deployable, package-per-module inside `com.ledgerflow`. `order`,
`payment`, `idempotency` and `outbox` have real code so far:

```
com.ledgerflow
├── config
│   └── SecurityConfig  the OAuth2 resource server filter chain (scopes, permitted paths)
├── order
│   ├── api      REST controllers, the exception handler, and DTO <-> entity mapping
│   ├── service  use cases (OrderService): orchestrates repo calls, re-validates before writing
│   ├── repo     Spring Data ReactiveCrudRepository interfaces
│   └── model
│       ├── (OrderStatus, InvalidStateTransitionException)
│       ├── entity      Spring Data R2DBC entities (OrderEntity, OrderItemEntity)
│       ├── dto         API request/response records (CreateOrderRequest, OrderResponse, ...)
│       └── constraint  shared Bean Validation constraints (@ValidCurrencyCode)
├── payment
│   ├── api       PaymentController, PaymentExceptionHandler, PaymentMapper
│   ├── service   PaymentService: creates a payment, calls the provider, drives the linked order
│   ├── repo      PaymentRepository (Spring Data ReactiveCrudRepository)
│   ├── provider  PaymentProvider interface and FakePaymentProvider, its deterministic stand-in
│   └── model
│       ├── (PaymentStatus, SimulatedOutcome, InvalidStateTransitionException)
│       ├── entity  Spring Data R2DBC entity (PaymentEntity)
│       └── dto     API request/response records (AuthorizePaymentRequest, PaymentResponse)
├── idempotency
│   ├── api      IdempotencyExceptionHandler only — idempotency has no controllers of its
│   │            own; its exceptions surface from other modules' endpoints
│   ├── service  IdempotencyService: claims/replays keys by Idempotency-Key + request hash;
│   │            IdempotencyProperties (poll retry/backoff config)
│   ├── repo     IdempotencyKeyRepository (Spring Data ReactiveCrudRepository)
│   └── model
│       ├── IdempotencyKeyStatus
│       └── entity  Spring Data R2DBC entity (IdempotencyKeyEntity)
└── outbox
    ├── service
    │   ├── OutboxService              appends event rows (called from other modules' transactions)
    │   ├── OutboxPublisher / OutboxPublisherScheduler   polls PENDING rows and dispatches them
    │   ├── OutboxEventDispatcher      claims (handler_name, event_id) and invokes handlers
    │   ├── OutboxEventHandler         interface implemented by each consumer
    │   ├── OutboxProperties           `ledgerflow.outbox.publisher.*` binding
    │   └── handler  EventReceiptRecorder and the three per-event-type receipt handlers
    ├── repo     OutboxEventRepository, OutboxConsumedEventRepository, OutboxEventReceiptRepository
    └── model
        ├── OutboxEventStatus
        ├── event   DomainEvent, OrderCreatedEvent, PaymentAuthorizedEvent, PaymentDeclinedEvent
        └── entity  OutboxEventEntity, OutboxConsumedEventEntity, OutboxEventReceiptEntity
```

`outbox` has no `api` package — nothing calls it over HTTP; `order` and
`payment` call `OutboxService` directly. See [Events and reliable
publication](#events-and-reliable-publication).

Dependencies point inward: `api` → `service` → `repo`, all depending on
`model`; `model` never depends back out on any of the other three, and
nothing outside `api` may depend on `api`. This is enforced by the ArchUnit
rules in `src/test/java/.../architecture` (`ModularityArchitectureTest`),
which also forbid `repo` from depending on `service`/`api`, and keep the
top-level module packages (currently `order`, `payment`, `idempotency` and
`outbox`) free of cycles — `ledger` and `common` should follow the same
four-package layout once they have real code. `payment` is one exception:
it adds a fifth package, `provider`, which those rules don't constrain.
`outbox` is the other: it has no `api` package at all. `payment.service`
also depends directly on `order.service.OrderService` to read and
transition orders, and `payment.api.PaymentController` depends on
`idempotency.service.IdempotencyService` to wrap payment authorization — a
dedicated ArchUnit rule (`idempotency_should_not_depend_on_business_modules`)
forbids the reverse, so `idempotency` may never depend on `order` or
`payment`. `order.service.OrderService` and `payment.service.PaymentService`
both depend on `outbox.service.OutboxService` to append events; a matching
rule (`outbox_should_not_depend_on_other_modules`) forbids `outbox` from
depending back on `order`, `payment` or `idempotency`. `PaymentEntity` also
reuses `order`'s `@ValidCurrencyCode` constraint rather than duplicating it.

## Running locally

Start PostgreSQL and Keycloak:

```bash
docker compose up -d
```

Keycloak comes up with no realm, client, or user provisioned — before the
app under the `local` profile will accept any request other than the
health check, create a `ledgerflow` realm, a client, and a user in the
Keycloak admin console at http://localhost:8180 (bootstrap admin
`admin`/`admin`), and issue that user tokens carrying the `order:*` /
`payment:*` scopes this app checks. See [Trust
boundaries](#trust-boundaries).

Run the app against it:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

Health check (no auth required): http://localhost:8080/actuator/health

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

Three test classes require Docker Desktop to be running, since they start a
real PostgreSQL container via Testcontainers: `LedgerflowApplicationTests`,
`OrderServiceIntegrationTest` and `PaymentControllerTest`. Contributors
without Docker available can run everything else — the entity and
validation unit tests, the `@WebFluxTest` controller slice, the
mock-based `PaymentServiceTest`, and the ArchUnit module-boundary checks —
by targeting those packages instead:

```powershell
.\gradlew.bat test --tests "com.ledgerflow.order.model.*" --tests "com.ledgerflow.order.api.*" --tests "com.ledgerflow.order.service.OrderServiceTest" --tests "com.ledgerflow.payment.model.*" --tests "com.ledgerflow.payment.provider.*" --tests "com.ledgerflow.payment.service.PaymentServiceTest" --tests "*Architecture*" --console=plain
```

## Configuration profiles

| File                      | Purpose                                            |
|---------------------------|-----------------------------------------------------|
| `application.yml`         | Base config shared by all profiles                  |
| `application-local.yml`   | Local dev; sets both the app's `spring.r2dbc.*` URL and Flyway's separate `spring.flyway.*` JDBC URL against `docker-compose` PostgreSQL |
| `application-test.yml`    | Test profile; both the R2DBC and JDBC connection details are supplied by Testcontainers via `@ServiceConnection` |

## Database migrations

Flyway migrations live in `src/main/resources/db/migration`, named
`V<version>__description.sql`. `V1__initial_schema.sql` is a placeholder;
`V2__create_orders_schema.sql` creates `orders`/`order_items`, and
`V3__create_payments_schema.sql` creates `payments` (foreign-keyed to
`orders.id`).

## Order API

The `order` module exposes three endpoints under `/api/v1/orders`. Every
endpoint requires a bearer JWT and a per-route scope (`order:create`,
`order:read`, `order:cancel`) — see [Trust boundaries](#trust-boundaries).
Errors are returned as RFC 7807 `ProblemDetail` bodies (see below).

### Create an order

`POST /api/v1/orders` → `201 Created`, with a `Location` header pointing at
the new order. `customerId` in the body must equal the caller's JWT `sub`
claim, or the request fails with `403` — see [Resource
ownership](#resource-ownership).

```bash
curl -i -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
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
curl http://localhost:8080/api/v1/orders/b6f1c8b0-6e0e-4b8a-9c2c-2c7a9b1f0a11 \
  -H "Authorization: Bearer <token>"
```

### Cancel an order

`POST /api/v1/orders/{orderId}/cancel` → `200 OK` with the same body shape,
`status` now `CANCELLED`. This is currently the only state transition
exposed over the API — see [Order state machine](#order-state-machine).

```bash
curl -X POST http://localhost:8080/api/v1/orders/b6f1c8b0-6e0e-4b8a-9c2c-2c7a9b1f0a11/cancel \
  -H "Authorization: Bearer <token>"
```

### Error responses

All three endpoints share one exception handler
(`OrderExceptionHandler`, scoped to `com.ledgerflow.order`), which always
returns a Spring `ProblemDetail`.

`401 Unauthorized` / `403 Forbidden` — missing/invalid token, missing
scope, or a resource-ownership mismatch; see [Trust
boundaries](#trust-boundaries).

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
`409 Conflict`. `cancel` is the only order transition exposed on the order
API itself; `payment`'s `authorize` endpoint drives a `PAYMENT_PENDING`
order to `CONFIRMED` or `FAILED` (see
[Payment state machine](#payment-state-machine)) — but nothing in this
codebase moves an order into `PAYMENT_PENDING` through the API yet, so
today that transition has to be done directly on the entity, the way the
payment tests do it.

## Payment API

The `payment` module exposes two endpoints under `/api/v1/payments`. Every
endpoint requires a bearer JWT and a per-route scope (`payment:process`,
`payment:read`) — see [Trust boundaries](#trust-boundaries). Errors are
returned as RFC 7807 `ProblemDetail` bodies (see below).

### Authorize a payment

`POST /api/v1/payments` → `201 Created`, with a `Location` header pointing
at the new payment. `orderId` must reference an order that is already
`PAYMENT_PENDING` and owned by the caller. `simulateOutcome` selects the
deterministic outcome `FakePaymentProvider` returns — `SUCCESS` (the
default if omitted), `DECLINE`, or `TIMEOUT` — see [Simulated
outcomes](#simulated-outcomes). This endpoint also requires an
`Idempotency-Key` header — see [Idempotency](#idempotency).

```bash
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -H "Idempotency-Key: 5b1f6c2e-1111-4b8a-9c2c-2c7a9b1f0a01" \
  -d '{
    "orderId": "b6f1c8b0-6e0e-4b8a-9c2c-2c7a9b1f0a11",
    "simulateOutcome": "SUCCESS"
  }'
```

Response (`201`, `Location: /api/v1/payments/1a2b3c4d-0000-4b8a-9c2c-2c7a9b1f0a22`):

```json
{
  "id": "1a2b3c4d-0000-4b8a-9c2c-2c7a9b1f0a22",
  "orderId": "b6f1c8b0-6e0e-4b8a-9c2c-2c7a9b1f0a11",
  "amount": 59.97,
  "currency": "USD",
  "status": "AUTHORIZED",
  "createdAt": "2026-09-14T10:16:00Z",
  "updatedAt": "2026-09-14T10:16:00Z"
}
```

### Fetch a payment

`GET /api/v1/payments/{paymentId}` → `200 OK` with the same body shape.

```bash
curl http://localhost:8080/api/v1/payments/1a2b3c4d-0000-4b8a-9c2c-2c7a9b1f0a22 \
  -H "Authorization: Bearer <token>"
```

### Simulated outcomes

`FakePaymentProvider` is the only `PaymentProvider` implementation so far —
a stand-in for a real processor until one is wired up. The outcome is
whatever `simulateOutcome` the caller passes, not derived from the amount,
the clock, or any random source, so every call is replayable:

| `simulateOutcome` | Payment status | Order status |
|--------------------|-----------------|---------------|
| `SUCCESS` (or omitted) | `AUTHORIZED` | `CONFIRMED` |
| `DECLINE`           | `DECLINED`      | `FAILED`      |
| `TIMEOUT`            | `TIMED_OUT`     | `FAILED`      |

`TIMEOUT` does not sleep — the provider signals a
`PaymentProviderTimeoutException` immediately.

### Error responses

Payment endpoints share one exception handler (`PaymentExceptionHandler`,
scoped to `com.ledgerflow.payment`), kept separate from
`OrderExceptionHandler` (scoped to `com.ledgerflow.order`, which never sees
exceptions raised from `PaymentController`).

`401 Unauthorized` / `403 Forbidden` — missing/invalid token, missing
scope, or a resource-ownership mismatch on the payment's order; see [Trust
boundaries](#trust-boundaries).

`404 Not Found` — no order exists with the given `orderId`, or no payment
exists with the given `paymentId`.

`409 Conflict` — the referenced order is not `PAYMENT_PENDING`:

```json
{
  "type": "about:blank",
  "title": "Order not payable",
  "status": 409,
  "detail": "order b6f1c8b0-6e0e-4b8a-9c2c-2c7a9b1f0a11 is not awaiting payment: CREATED"
}
```

`400 Bad Request` — request validation failure (missing `orderId`), a
missing `Idempotency-Key` header, or a `ConstraintViolationException` from
`PaymentService`'s persistence-boundary validation, in the same `errors`
array shape as the order API. See [Idempotency](#idempotency) for the
other `Idempotency-Key`-related status codes (`409`, `422`).

## Payment state machine

A payment moves through four states (`PaymentStatus`): `PENDING`,
`AUTHORIZED`, `DECLINED`, `TIMED_OUT`. `AUTHORIZED`, `DECLINED` and
`TIMED_OUT` are all terminal.

Valid transitions, enforced by `PaymentEntity.transitionTo` (mirrors
`OrderEntity`'s pattern):

- `PENDING` → `AUTHORIZED`
- `PENDING` → `DECLINED`
- `PENDING` → `TIMED_OUT`

`PaymentService.authorize` creates a payment in `PENDING` for the given
order, calls the `PaymentProvider`, and applies the outcome to the payment
and the linked order in the same step (see
[Simulated outcomes](#simulated-outcomes)). Any other payment transition
throws `InvalidStateTransitionException`, mapped to `409 Conflict` by
`PaymentExceptionHandler`.

## Trust boundaries

### Authentication and scopes

Every route is authenticated except the three `SecurityConfig` permits
outright: `GET /actuator/health`, `GET /actuator/health/**` and `GET /actuator/info`. Every other
route needs a valid JWT bearer token from the configured OAuth2 resource
server, plus the scope below, checked as a Spring Security `SCOPE_*`
authority on the route (`hasAuthority(...)` in `SecurityConfig`, not
application code):

| Route                                  | Required scope     |
|-----------------------------------------|---------------------|
| `POST /api/v1/orders`                   | `order:create`       |
| `GET /api/v1/orders/{orderId}`          | `order:read`         |
| `POST /api/v1/orders/{orderId}/cancel`  | `order:cancel`        |
| `POST /api/v1/payments`                 | `payment:process`     |
| `GET /api/v1/payments/{paymentId}`      | `payment:read`        |
| anything else                            | denied (`anyExchange().denyAll()`) |

A missing token, or one the resource server can't validate (expired, wrong
issuer, bad signature), gets `401 Unauthorized` — the default behavior of
Spring Security's `oauth2ResourceServer().jwt(...)`, not custom code here.
A valid token missing the required scope gets `403 Forbidden`, also the
framework default for a `hasAuthority(...)` mismatch.

### Resource ownership

Beyond the scope check, `OrderService` and `PaymentService` compare the
JWT `sub` claim (`jwt.getSubject()`, read in the controllers) against the
resource's owner:

- **Orders** — `OrderService.create`/`get`/`cancel` (the `(id, customerId)`
  overloads used by `OrderController`) call `requireOwnedBy`, which checks
  `order.getCustomerId().equals(customerId)` and throws
  `OrderAccessDeniedException` (→ `403 Forbidden`) on mismatch. On
  `POST /api/v1/orders`, `customerId` comes from the request body
  (`CreateOrderRequest.customerId`), not the token, and that same ownership
  check runs on the order being created — so creating an order with a
  `customerId` that doesn't match the caller's `sub` fails with `403`
  rather than silently assigning the order to someone else.
- **Payments** — `PaymentService.get(paymentId, customerId)` and
  `PaymentService.authorize(orderId, outcome, customerId)` inherit
  ownership through the linked order by calling
  `OrderService.get(orderId, customerId)`, so a payment is only
  visible/authorizable to the customer who owns its order.

What this does **not** protect against:
- It trusts the IdP's `sub` claim completely — any validly signed JWT for
  a given `sub` is treated as that customer, with no additional binding
  (e.g. to a device or session).
- There is no admin/support role or ownership override anywhere in
  `SecurityConfig`, `OrderService`, or `PaymentService` — no principal can
  read or act on another customer's orders or payments.
- A stolen but still-valid token is trusted for its full remaining
  lifetime; there is no revocation, session, or replay check beyond
  standard JWT expiry validation.
- Ownership is enforced in the service layer, as a second check after the
  route-level scope check in `SecurityConfig` — the two checks are
  independent, so a new service method that forgets to call
  `requireOwnedBy` (or the payment-side equivalent) would compile and pass
  the scope check while skipping ownership entirely.

### Logging

JWTs, the `Authorization` header, the `Idempotency-Key` header, and
request/response bodies must never be logged. As of this iteration, no
code under `src/main` logs anything at all — a grep for `log.`, `@Slf4j`,
`Logger`, and `System.out` across `src/main` returns no matches. However,
both `application-local.yml` and `application-test.yml` set
`logging.level.com.ledgerflow: DEBUG`, so any logging statement added
later under `com.ledgerflow` at `DEBUG` or above must not include these
values.

### Local Keycloak vs. tests

`docker-compose.yml` runs a local, dev-mode Keycloak
(`quay.io/keycloak/keycloak:26.0.7`, `start-dev`, no persistent volume) on
port 8180, with a bootstrap admin login (`admin`/`admin`) for its admin
console. Nothing is auto-provisioned: no realm, client, or user exists
until a developer creates them by hand. `application-local.yml` points the
resource server at issuer `http://localhost:8180/realms/ledgerflow`, so
the realm must be named `ledgerflow`, and its client/user must be set up
to issue tokens with the `order:*`/`payment:*` scopes above and a `sub`
matching the `customerId` used in requests.

Tests never talk to Keycloak. `application-test.yml` points
`jwk-set-uri` at a dummy, unreachable URL — by its own comment, this value
"is never fetched" because the test suite authenticates requests with
spring-security-test's `mockJwt()`, which populates the reactive security
context directly instead of validating a real token.

## Idempotency

`POST /api/v1/payments` requires an `Idempotency-Key` request header
(`PaymentController`, `@RequestHeader` with no `required = false`),
enforced by `IdempotencyService.execute` and backed by the
`idempotency_keys` table (`V4__create_idempotency_keys_schema.sql`). Keys
are scoped per authenticated subject: the table's unique constraint is on
`(owner_id, idempotency_key)`, where `owner_id` is `jwt.getSubject()` — so
the same key string can be reused independently by two different
customers, but not reused by the same customer for a different request.

- **Missing header** → `400 Bad Request`
  (`MissingRequestValueException`, handled by `PaymentExceptionHandler`,
  title "Missing request value").
- **New key** → the request claims the key row (an insert; a
  unique-constraint violation means another request already holds it),
  runs the payment authorization, and updates the row to `COMPLETED` with
  the serialized response status, headers, and body.
- **Same key, same request body** (request bodies are compared by SHA-256
  hash of the serialized JSON) → once the original request reaches
  `COMPLETED`, its stored response is replayed verbatim — same status,
  headers, and body — instead of re-running the authorization.
- **Same key, different request body** → `422 Unprocessable Entity`
  (`IdempotencyKeyReusedException`, title "Idempotency key reused").
- **Concurrent request with the same key still in progress** — the request
  that didn't win the claim polls for completion with backoff
  (`Retry.backoff`, configured by `ledgerflow.idempotency.max-poll-attempts`
  / `.min-poll-backoff` / `.max-poll-backoff` — `8` attempts between `50ms`
  and `1s` by default). If the original request completes within that
  window, it returns the replayed response; if the attempts are exhausted
  first, it fails with `409 Conflict` (`IdempotencyKeyInProgressException`,
  title "Request in progress").
- **If the payment authorization fails** (any exception from the wrapped
  action — `PaymentService.authorize` plus the response mapping — while the
  request is still running), the claimed key row is deleted rather than
  marked `COMPLETED` — a subsequent retry with the same key and the same
  body is treated as a brand-new attempt, not a replay. This is the only
  case that deletes the row; see **Limitations** below for the cases where
  a claim is left behind instead.

### Limitations

`IdempotencyService.perform` only deletes the claimed row inside the
`onErrorResume` on the wrapped action described above. Three other cases
leave the row stuck in `IN_PROGRESS` forever, since `idempotency_keys`
(`V4__create_idempotency_keys_schema.sql`) has no expiry or lease column:

- The client disconnects or cancels the request mid-flight. This is
  deliberate, not an oversight — the payment provider may already have
  been invoked by that point, so deleting the claim on cancellation could
  let a retried request double-charge.
- The application crashes mid-request.
- The payment authorization succeeds but persisting the `COMPLETED`
  result afterwards (`IdempotencyService.complete`) fails — that happens
  after the `onErrorResume` guard, so the row is never deleted.

In all three cases, every subsequent request with that key gets `409
Conflict` (`IdempotencyKeyInProgressException`) until the row is removed
manually. A lease/expiry column on the claim is the intended fix, not yet
implemented.

## Events and reliable publication

`order` and `payment` writes append a domain event to the `outbox` module
instead of calling any consumer directly. `OutboxService.append` is
`@Transactional(propagation = Propagation.MANDATORY)` — it fails if no
transaction is already active — so it can only ever run inside the
caller's own `@Transactional` method, never on its own.

### Domain events

Three events exist so far, all implementing `outbox.model.event.DomainEvent`
and all at schema version `1` (each record's `SCHEMA_VERSION` constant):

| Event | `event_type` | Appended from | Payload |
|---|---|---|---|
| `OrderCreatedEvent` | `OrderCreated` | `OrderService.save`, only when the order being saved `isNew()` (not on later updates, e.g. `cancel`) | `orderId`, `customerId`, `totalAmount`, `currency`, `status`, `createdAt` |
| `PaymentAuthorizedEvent` | `PaymentAuthorized` | `PaymentService.complete`, when the payment settles as `AUTHORIZED` | `paymentId`, `orderId`, `customerId`, `amount`, `currency`, `status`, `authorizedAt` |
| `PaymentDeclinedEvent` | `PaymentDeclined` | `PaymentService.complete`, when the payment settles as `DECLINED` **or** `TIMED_OUT` | `paymentId`, `orderId`, `customerId`, `amount`, `currency`, `status`, `declinedAt` |

`PaymentDeclinedEvent` is emitted for both terminal failure outcomes (see
[Simulated outcomes](#simulated-outcomes)) — a consumer tells them apart by
the payload's `status` field, which carries `payment.getStatusCode()`
(`"DECLINED"` or `"TIMED_OUT"`, the `PaymentStatus` enum name), not by a
different `event_type`.

### Same-transaction write

`OutboxService.append` inserts into `outbox_events` from inside the same
`@Transactional` method that writes the order or payment row —
`OrderService.save` and `PaymentService.complete` (called from
`PaymentService.authorize`) both call it before returning. This guarantees
that if the business write commits, its event row also commits and is
never lost, and if the business write rolls back, no event row exists
either. It does **not** guarantee that the event is published immediately:
publication happens later, asynchronously, via the poller described below.
It also does not guarantee cross-event ordering beyond whatever
`OutboxEventRepository.findPendingBatch`'s `ORDER BY created_at, id`
produces, and there is no external broker in this codebase yet — publish
means invoking in-process `OutboxEventHandler` beans directly (see
[Scope](#explicitly-out-of-scope-for-this-iteration)).

### Delivery semantics: at-least-once, not exactly-once

`OutboxPublisherScheduler` polls on a fixed delay and hands each `PENDING`
batch to `OutboxPublisher.publishPendingBatch`, which calls
`OutboxEventDispatcher.dispatch` for every event and then
`OutboxService.markPublished` only after every registered
`OutboxEventHandler` for that event's `event_type` has run. For each
handler, `OutboxEventDispatcher` wraps a claim insert into
`outbox_consumed_events` (`(handler_name, event_id)`, unique-constrained —
`uq_outbox_consumed_events_handler_event`) and the handler's own
`handle(...)` call in one database transaction. A duplicate-key violation
on that insert means the handler already consumed this event; it's caught
and treated as a no-op rather than an error.

Delivery is **at-least-once**. If the process crashes (or a later handler
in the same dispatch fails) after one handler's claim-and-handle
transaction has committed but before `markPublished` commits, the event
row is still `PENDING` and gets redelivered on the next poll. Each
handler's own claim then hits the unique constraint and is skipped, so
that handler's registered database effect runs at most once per event —
but this only protects work done inside the claim's transaction. Any
side effect a handler performs outside that transaction (e.g. an outbound
HTTP call) is not covered by the claim and could repeat on redelivery.
The only handlers registered today (`EventReceiptRecorder`, via
`OrderCreatedReceiptHandler` / `PaymentAuthorizedReceiptHandler` /
`PaymentDeclinedReceiptHandler`) insert a row into `outbox_event_receipts`
inside that same transaction, so they are fully covered by this guarantee.

### Status lifecycle

`outbox_events.status` (`OutboxEventStatus`) is `PENDING`, `PUBLISHED`, or
`FAILED` (`ck_outbox_events_status` also enforces this at the database
level). `findPendingBatch` only ever selects `status = 'PENDING'`, so:

- **`PENDING` → `PUBLISHED`** — every handler for the event succeeded;
  `published_at` is stamped.
- **`PENDING` → `FAILED`** — `OutboxPublisher.recordFailedAttempt`
  increments `attempts` and stores the exception's class and message in
  `last_error` (truncated to 1000 characters, `LAST_ERROR_MAX_LENGTH`) on
  every failed dispatch; once `attempts` reaches
  `ledgerflow.outbox.publisher.max-attempts`, the row moves to `FAILED`.
  Between failures the row stays `PENDING` and is retried on the next
  poll — the poll interval is the only backoff, there is no separate
  retry delay.
- **`FAILED` is terminal.** Because `findPendingBatch` filters on
  `status = 'PENDING'`, a `FAILED` row is never picked up again on its
  own. It is kept in the table (never deleted) with its `attempts` and
  `last_error` for inspection.

Inspect failed rows:

```sql
SELECT id, event_type, aggregate_id, attempts, last_error, created_at
FROM outbox_events
WHERE status = 'FAILED'
ORDER BY created_at;
```

Recover one manually by putting it back into the poll query's result set:

```sql
UPDATE outbox_events
SET status = 'PENDING', attempts = 0, last_error = NULL
WHERE id = '<event-id>';
```

Resetting `status` to `PENDING` is what makes `findPendingBatch` pick the
row up again; resetting `attempts` to `0` restores its full retry budget
before it can reach `FAILED` again.

### Configuration

`OutboxProperties`, bound from `ledgerflow.outbox.publisher.*`:

| Property | Type | Default (`application.yml`) | Purpose |
|---|---|---|---|
| `ledgerflow.outbox.publisher.enabled` | `boolean` | `true` | Gates `OutboxPublisherScheduler` via `@ConditionalOnProperty` — when `false`, the scheduler bean is never created, not merely a no-op. `application-test.yml` sets this to `false`. |
| `ledgerflow.outbox.publisher.poll-interval` | `Duration` | `PT1S` | `@Scheduled(fixedDelayString = ...)` delay between the end of one poll and the start of the next. |
| `ledgerflow.outbox.publisher.batch-size` | `int` | `50` | Rows fetched per poll (`findPendingBatch`'s `LIMIT`). |
| `ledgerflow.outbox.publisher.max-attempts` | `int` | `5` | Failed dispatch attempts before a row moves to `FAILED`. |

### Scope

Publishing and consuming both happen in-process: `OutboxPublisher` calls
registered `OutboxEventHandler` beans directly inside the application. There
is no Kafka or other message broker in front of `outbox_events`, and no
notification/webhook module consumes these events yet — see [Explicitly
out of scope for this iteration](#explicitly-out-of-scope-for-this-iteration).

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
- **Money is `BigDecimal` end to end** (`totalAmount`, `unitPrice`,
  `PaymentEntity.amount`), backed by `numeric(19, 2)` columns — no
  `double`/`float` anywhere in the order or payment model.
- **`FakePaymentProvider`'s outcome is an explicit field, not derived from
  the amount.** `AuthorizePaymentRequest.simulateOutcome` names the outcome
  directly (`SUCCESS`/`DECLINE`/`TIMEOUT`), so every one of the three code
  paths through `PaymentService.settle` can be exercised on demand and
  replayed deterministically, rather than relying on magic amounts to
  trigger a decline or a timeout.

## Explicitly out of scope for this iteration

- Kafka, Redis, Kubernetes, and any frontend — not added yet.
- An external message broker for the outbox, and any real event consumer.
  `OutboxPublisher` polls `outbox_events` and dispatches straight to
  in-process `OutboxEventHandler` beans — no queue or broker sits between
  them — and the only handlers registered today (`EventReceiptRecorder`
  and its three per-event-type wrappers) just record a receipt row rather
  than notifying another service. See [Events and reliable
  publication](#events-and-reliable-publication).
- A real payment gateway. `PaymentProvider` has one implementation,
  `FakePaymentProvider`, which returns whichever outcome the caller asks
  for instead of calling an actual processor.
- Driving an order into `PAYMENT_PENDING` through the API. The transition
  rule exists on `OrderEntity` and `payment`'s `authorize` endpoint requires
  it, but no endpoint in this codebase performs it — see
  [Order state machine](#order-state-machine).
- An admin/support role, token revocation, or any override of the
  resource-ownership check — see [Trust boundaries](#trust-boundaries).
- A production-ready Keycloak setup. The `docker-compose.yml` `keycloak`
  service is dev-only (`start-dev`, no persistent realm export) and
  requires manual realm/client/user provisioning — see [Trust
  boundaries](#trust-boundaries).
- The `ledger` and `common` module packages: removed rather than kept as
  empty scaffolding, since they have no code yet. Re-add them with the
  same layout as `order`, `payment`, `idempotency` and `outbox` once they
  have real behaviour.
