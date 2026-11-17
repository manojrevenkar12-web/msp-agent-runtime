# 3. Transactional outbox and partitioned queues with a single active consumer

**Context.** A webhook must be acknowledged quickly and never lost; events of one ticket should be handled in order
so that time capture sees the session before the note about it most of the time.

**Decision.** The webhook handler records the delivery and writes the event to an outbox table in one transaction.
A relay (SKIP LOCKED, so several instances can run) publishes to a topic exchange with a routing key per partition;
the partition comes from the ticket (or customer) id. Each partition queue has `x-single-active-consumer`, so one
consumer at a time handles a ticket's events, in order, across instances, and a standby takes over when it dies. A
consumer acknowledges after the engine returns; a message no consumer can take goes to a dead-letter queue. On one
node the relay can hand events to the engine directly (`msp.bus: inline`).

**Consequences.** Order holds per ticket, not globally; the agents do not depend on it (they reconcile against the
current state), it only makes the common case cleaner. Throughput scales with partitions.
