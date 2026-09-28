# ledgerflow — agent notes

Java 21 / Spring Boot 3.3 modular monolith (WebFlux + R2DBC), Gradle-built,
Postgres-backed. See `README.md` for the full stack, API docs, and
design-decision rationale — this file only covers what an agent needs before
touching code.

## Build / test

- Windows: `.\gradlew.bat clean build`. Linux/CI: `./gradlew clean build`.
- Nine test classes need Docker Desktop running (Testcontainers Postgres) —
  see [Running tests without Docker](README.md#running-tests-without-docker)
  in the README for the full list and a working filter command. `order.api`
  and `payment.api` each mix Docker and non-Docker test classes in the same
  package, so skip the Docker ones by class name, not package wildcard.
- Local Postgres for `bootRun`: `docker compose up -d`, then
  `.\gradlew.bat bootRun --args='--spring.profiles.active=local'`.
- Single deployable; `order`, `payment`, `idempotency` and `outbox` are the
  business modules with real code so far, each under `com.ledgerflow.<module>`
  with the same `api` / `service` / `repo` / `model` package layout (`payment`
  adds a fifth `provider` package; `outbox` has no `api` package since nothing
  calls it over HTTP). `ledger` is the next business module to add, with the
  same layout. `common` holds cross-cutting infrastructure (correlation IDs,
  tracing context) and is deliberately exempt from the four-package layout.

## Architecture rules (enforced by ArchUnit, `architecture` test package)

- Dependencies point inward only: `api` → `service` → `repo`, all → `model`.
  `model` never depends back out on the other three.
- Top-level module packages (`order`, `payment`, `idempotency`, `outbox`, and
  future modules such as `ledger`) must stay free of cross-module cycles.
- Breaking either rule fails the build via `ModularityArchitectureTest`, not
  just a lint warning — fix the dependency direction, don't suppress the test.

## Reactive/R2DBC gotchas

- Everything is reactive end-to-end (`Mono`/`Flux`) in `api`/`service`/`repo`
  — no blocking calls there. Flyway is the one deliberate exception (below).
- New entities using `@Version` must implement `Persistable<ID>` with an
  explicit `@Transient boolean isNew` flag (see `OrderEntity`). Without it,
  Spring Data R2DBC treats a freshly-built, non-null-version entity as "not
  new" and a plain `save()` silently no-ops instead of inserting.
- Flyway runs over blocking JDBC (`org.postgresql:postgresql` stays on the
  classpath for this reason, alongside the R2DBC driver) — `spring.flyway.*`
  is configured separately from `spring.r2dbc.*` in each profile.
- Validate entities with an injected `jakarta.validation.Validator` right
  before persisting (see `OrderService`), not hand-written `if (...) throw`
  checks — DTOs and entities share the same Bean Validation constraints,
  including the custom `@ValidCurrencyCode`.

## Git / attribution

- No AI agent, including Claude, is ever listed as a commit author/committer,
  a `Co-authored-by` trailer, or a repo contributor.
- No AI agent pushes to the remote on its own initiative — only when the
  user explicitly asks.

## Conventions

- Follow SOLID and Spring's own recommended patterns/idioms (constructor
  injection, `@ConfigurationProperties` over scattered `@Value`, Spring Data
  repository conventions, the reactive idioms already established here)
  rather than ad-hoc alternatives.
- No code comments unless the user explicitly asks for them.
- Always use Lombok, the appropriate Spring module, and well-established
  public libraries wherever they fit, rather than hand-rolling equivalent
  code.
- Lombok on entities/services/controllers (`@Getter`, `@Builder`,
  `@RequiredArgsConstructor`); `model.dto` types stay plain Java records.
- Errors are RFC 7807 `ProblemDetail` via a per-module exception handler
  (e.g. `OrderExceptionHandler`, scoped to `com.ledgerflow.order`) — add one
  per new module rather than a shared bespoke error DTO.
- Money is `BigDecimal` / `numeric(19,2)` end to end — never `double`/`float`.
- Migrations live in `src/main/resources/db/migration`, named
  `V<n>__description.sql`. Never edit an already-applied migration; add a
  new one.
