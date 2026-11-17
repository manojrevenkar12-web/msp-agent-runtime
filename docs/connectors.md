# Connector API

With `msp.connectors.mode: http` the runtime reaches a tenant's systems through one REST API at
`msp.connectors.base-url`. A ticket system, RMM or ERP implements it once (or a small adapter in front of it does).
Every request carries `X-Tenant`; every write carries `Idempotency-Key`, which the system stores with the write.

| Method and path | Returns or accepts |
|---|---|
| `GET /tickets/{id}` | `{id, customerId, subject, body, createdAt, status, category, priority, assignee, closedAt}` |
| `GET /tickets/{id}/notes` | `[{id, ticketId, technicianId, writtenAt, text, internal}]` |
| `GET /tickets/{id}/time-entries` | `[{id, ticketId, customerId, technicianId, start, minutes, kind, source, status, externalRef}]` |
| `GET /customers/{id}/time-entries?month=YYYY-MM&status=CONFIRMED` | the same, for billing |
| `GET /technicians` | `[{id, name, skills, openTickets}]` |
| `GET /tickets/{id}/remote-sessions` | `[{id, ticketId, technicianId, start, end}]` (RMM) |
| `GET /tickets/{id}/visits` | `[{id, ticketId, technicianId, start, end}]` (field app) |
| `GET /customers/{id}/contract` | `{customerId, customerName, type, sla, hourlyRateCents, flatFeeCents, prepaidMinutes, travelFlatCents}` |
| `PUT /tickets/{id}/triage` | `{priority, category, assignee, needsReview, reason}` |
| `POST /tickets/{id}/time-entries` | a time entry |
| `POST /time-entries/{id}/void` | `{}` |
| `POST /tickets/{id}/updates` | `{text}`, a message to the customer |
| `POST /invoices` | `{customerId, month, lines: [{kind, ticketId, minutes, amountCents, text}], externalRef}` |
| `GET /writes/{idempotencyKey}` | 200 if a write with this key exists, 404 if not |

A 404 for a ticket or customer fails the run; any other error is retried with backoff.
