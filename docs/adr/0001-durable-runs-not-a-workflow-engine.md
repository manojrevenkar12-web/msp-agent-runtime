# 1. Durable runs in the runtime itself, not a workflow engine

**Context.** Agents at a service provider react to events that arrive at least once, out of order, sometimes hours
late. A run calls a model (slow, sometimes throttled) and writes to systems that must not see a write twice: an invoice
in the ERP, a message to a customer. Workers die. Temporal or a BPMN engine would give durable execution, at the price
of a second platform to run for a team that writes Java and already runs PostgreSQL and RabbitMQ.

**Decision.** The engine keeps every step's result in PostgreSQL (`steps`) and every outside write under an
idempotency key (`effects`). A resumed run replays kept steps instead of repeating them. A write that started but was
never recorded is checked against the outside system (`exists(key)`) before it is made again. Runs are leased, so a
dead worker's run is taken over when its lease runs out; retries back off exponentially and stop after
`maxAttempts`. A run waiting for a person is parked, not polled.

**Consequences.** Agents are plain code with two rules: read in `ctx.step`, write in `ctx.effect`. Every connector
must accept an idempotency key and answer whether a write with that key exists; the connector API (docs/connectors.md)
requires both. A step's result must be serialisable, which also makes every decision inspectable in the console.
