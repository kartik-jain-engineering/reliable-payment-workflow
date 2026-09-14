# ledgerflow

A modular monolith for processing payment workflows reliably (idempotent
requests, transactional outbox for events, auditable ledger postings).

**Status:** initial scaffolding only. No business logic is implemented yet —
this iteration establishes the project structure, build, config, database
migration pipeline, and test setup that later work will build on.

## Stack

- Java 21
- Spring Boot 3.3 (Web, Data JPA, Actuator)
- Gradle (wrapper committed — no local Gradle install required)
- PostgreSQL + Flyway
- JUnit 5, Testcontainers, ArchUnit

## Module layout

Single deployable, package-per-module inside `com.ledgerflow`:

```
com.ledgerflow
├── common          shared kernel (types every module may depend on)
├── payment         core payment workflow module
├── ledger          balance / posting module
├── idempotency     duplicate-request detection module
└── outbox          transactional outbox for reliable event publishing
```

Each module is layered as `domain` → `application` → `infrastructure`
(dependencies point inward). Modules are kept in one Maven-style build
rather than separate build modules for simplicity; boundaries are enforced
by the ArchUnit test in `src/test/java/.../architecture` instead, and
tightened as real code lands in each module.

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

## Configuration profiles

| File                      | Purpose                                            |
|---------------------------|-----------------------------------------------------|
| `application.yml`         | Base config shared by all profiles                  |
| `application-local.yml`   | Local dev, points at `docker-compose` PostgreSQL     |
| `application-test.yml`    | Test profile; datasource supplied by Testcontainers  |

## Database migrations

Flyway migrations live in `src/main/resources/db/migration`, named
`V<version>__description.sql`. `V1__initial_schema.sql` is currently a
placeholder — the first real domain migration should be added as
`V2__....sql`.

## Explicitly out of scope for this iteration

Kafka, Redis, Keycloak, Kubernetes, and any frontend. Not added yet.
