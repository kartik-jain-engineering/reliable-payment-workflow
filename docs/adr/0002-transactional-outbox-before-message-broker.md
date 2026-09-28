# 2. Transactional outbox before any message broker

## Context

`order` and `payment` writes (order creation, payment settlement) need to
notify the rest of the system without losing events on a crash between "the
business write committed" and "the event was published" — the classic
dual-write problem, worse in a reactive/non-transactional-messaging stack
where there is no JMS/Kafka transaction to piggyback on. Standing up a real
broker (Kafka, RabbitMQ) is explicitly out of scope for this stage of the
project (see [Explicitly out of scope for this
iteration](../../README.md#explicitly-out-of-scope-for-this-iteration)), but
the reliability guarantee is needed now, not after a broker is chosen.

## Decision

Use the transactional outbox pattern with no broker in front of it yet:

- `outbox.service.OutboxService.append` is
  `@Transactional(propagation = Propagation.MANDATORY)` — it only runs
  inside an already-open transaction, so `OrderService.save` and
  `PaymentService.complete` write their `outbox_events` row in the same
  database transaction as the `orders`/`payments` row. If the business
  write commits, the event row commits with it; if it rolls back, no event
  row exists either.
- `OutboxPublisherScheduler` polls `outbox_events` on a fixed delay
  (`ledgerflow.outbox.publisher.poll-interval`) and `OutboxPublisher`
  dispatches each `PENDING` row straight to in-process
  `OutboxEventHandler` beans — no queue or broker sits between the outbox
  table and its consumers.
- Delivery is at-least-once: a crash between a handler committing its claim
  and the event being marked `PUBLISHED` causes redelivery on the next
  poll. Each handler's own unique-constrained claim
  (`outbox_consumed_events`, `(handler_name, event_id)`) makes that
  redelivery a no-op for work done inside the claim transaction — see
  [Events and reliable
  publication](../../README.md#events-and-reliable-publication).

## Consequences

- Reliable publication exists today without a broker dependency, and
  without changing how `order`/`payment` write their business rows — they
  just call `OutboxService.append` in the transaction they already have.
- Ordering is only best-effort (`findPendingBatch`'s
  `ORDER BY created_at, id`), not a hard guarantee, and there is no
  cross-service consumer yet — the only handlers registered today record a
  receipt row in the same database, not a call to another system.
- Adding a real broker later means adding a consumer that reads
  `outbox_events` (or is fed by a CDC process on that table) and publishes
  to the broker — the write side (`OutboxService.append`,
  `Propagation.MANDATORY`) does not need to change.
- Any side effect a future `OutboxEventHandler` performs outside its claim
  transaction (e.g. an outbound HTTP call) is not covered by the
  at-least-once guarantee and could repeat on redelivery — this has to be
  designed for explicitly in each new handler, not assumed away by the
  outbox mechanism.
