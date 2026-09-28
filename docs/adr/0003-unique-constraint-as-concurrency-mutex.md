# 3. A database unique constraint as the concurrency mutex, reused twice

## Context

Two unrelated problems in this codebase both reduce to the same question:
"did I win the race to be the first to handle this key?"

- **Idempotency**: when two requests arrive with the same
  `Idempotency-Key` for the same customer, exactly one of them should run
  `PaymentService.authorize`; the other should wait for or replay its
  result.
- **Outbox consumption**: when an outbox event is redelivered (at-least-once
  delivery, see [ADR 2](0002-transactional-outbox-before-message-broker.md)),
  each `OutboxEventHandler` should run its effect at most once per event,
  even if `OutboxPublisher` dispatches the same event to it twice.

The application is reactive and runs as a single process today, but neither
problem can be solved with an in-process lock (a `synchronized` block or a
`ConcurrentHashMap`) without it becoming wrong the moment there is more than
one instance of the app running — and R2DBC's optimistic locking
(`@Version`) only protects against concurrent *updates* to a row that
already exists, not against two requests racing to be the first to *create*
it.

## Decision

Give each "claim" its own row, protected by a database unique constraint,
and treat a unique-constraint violation on insert as "someone else already
holds this":

- `idempotency_keys` has `CONSTRAINT uq_idempotency_keys_owner_key UNIQUE
  (owner_id, idempotency_key)`. `IdempotencyService` tries to insert a claim
  row; a `DuplicateKeyException` means another request already claimed that
  key for that customer, so this request polls for the original's
  completion instead (`Retry.backoff`, bounded by
  `ledgerflow.idempotency.max-poll-attempts`).
- `outbox_consumed_events` has `CONSTRAINT
  uq_outbox_consumed_events_handler_event UNIQUE (handler_name, event_id)`.
  `OutboxEventDispatcher` wraps a claim insert into this table and the
  handler's `handle(...)` call in one transaction; a duplicate-key
  violation means this handler already consumed this event, so it's caught
  and treated as a no-op.

Both tables use the identical shape for the same reason: an insert either
succeeds (this caller is first) or fails on the unique constraint (someone
else was first), decided atomically by PostgreSQL itself rather than by any
coordination in application code.

## Consequences

- The mutex works correctly regardless of how many application instances
  are running, with no external lock service (e.g. Redis, ZooKeeper) — the
  database that already holds the data being protected also holds the
  lock.
- Neither table has an expiry or lease column. If a claim holder crashes or
  hangs after claiming but before completing, its row is stuck
  `IN_PROGRESS` (idempotency) or simply never inserted into
  `outbox_consumed_events` (outbox — handled instead by the outbox row
  staying `PENDING` for the next poll). For idempotency, every subsequent
  request with that key gets `409 Conflict` until the row is removed
  manually — a known, documented limitation (see
  [Idempotency → Limitations](../../README.md#limitations)), not yet fixed
  by a lease/expiry column.
- Correctness depends on the constraint existing in the schema, not on any
  Java-level check-then-insert logic — the constraint must be preserved by
  every future migration touching these tables, since removing it would
  silently reintroduce the race it prevents.
