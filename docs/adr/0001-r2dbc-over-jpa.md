# 1. R2DBC over JPA for the reactive stack

## Context

`ledgerflow` is built on Spring WebFlux end to end — controllers, services
and repositories all return `Mono`/`Flux`. JPA/Hibernate is blocking:
running it under WebFlux would mean either blocking the Reactor event loop
threads directly, or wrapping every repository call on a bounded elastic
scheduler, defeating the point of a non-blocking stack.

## Decision

Persistence uses Spring Data R2DBC against PostgreSQL, not Spring Data JPA.
This has consequences that show up throughout `order` and `payment`:

- R2DBC has no aggregate/cascade mechanism equivalent to JPA's
  `@OneToMany`/Hibernate's cascading `save()`. `OrderService.save` persists
  the `orders` row, then explicitly deletes and re-inserts all
  `order_items` rows rather than relying on a framework cascade.
- R2DBC's default insert/update heuristic misclassifies a
  brand-new, `@Version`-annotated entity as "not new" once its version is
  seeded. `OrderEntity`/`PaymentEntity` implement `Persistable<UUID>` with an
  explicit `@Transient isNew` flag to force the correct insert/update
  decision.
- Flyway has no reactive driver, so it still runs over blocking JDBC.
  `org.postgresql:postgresql` (blocking) stays on the runtime classpath
  purely for Flyway, alongside `org.postgresql:r2dbc-postgresql` for the
  application itself.
- There is no `ddl-auto: validate` equivalent — R2DBC never compares entity
  mappings to the live schema at startup. Drift between an entity and the
  Flyway-managed schema surfaces only at query time.

## Consequences

- The application composes cleanly with the rest of the reactive stack
  (WebFlux controllers, Reactor-based resilience operators in
  `PaymentService.settle`) with no thread-pool bridging anywhere in the
  request path.
- Several R2DBC/Spring Data gaps (cascading writes, insert/update detection,
  no startup schema validation) had to be worked around explicitly in
  application code rather than being handled by the framework — see
  [Design decisions](../../README.md#design-decisions) in the main README
  for the specific mechanisms.
- The compensating control for the missing schema-validation safety net is
  integration coverage: `OrderServiceIntegrationTest` and
  `LedgerflowApplicationTests` round-trip every column of the Flyway-managed
  schema against a real Testcontainers PostgreSQL instance.
