# HTTP API

Every `/api` call needs `X-Api-Key` (or `Authorization: Bearer`). A key belongs to one tenant and one role: VIEWER
reads, OPERATOR also approves, tests and uses the sandbox, ADMIN also creates and publishes versions. Every query is
scoped to the key's tenant. Errors are `{"error": "..."}`.

| Method and path | Role | What |
|---|---|---|
| `POST /hooks/{tenant}/{source}` | signature | `{"id","type","occurredAt","data"}`, header `X-Msp-Signature: sha256=<HMAC-SHA256(secret, body)>`; 202 `{"accepted","duplicate"}` |
| `GET /api/overview` | VIEWER | runs by agent and status, waiting approvals, retries, tokens and cost, latency (last day), the evaluation |
| `GET /api/runs?agent=&status=&limit=` | VIEWER | run summaries, newest first |
| `GET /api/runs/{id}` | VIEWER | a run with its kept steps and spans |
| `GET /api/runs/{id}/otlp` | VIEWER | the run's spans as an OTLP/HTTP JSON body |
| `POST /api/runs/{id}/retry` | OPERATOR | a dead or failed run gets new attempts, from its kept steps |
| `GET /api/agents` | VIEWER | agents, settings with limits, versions |
| `POST /api/agents/{id}/versions` | ADMIN | `{"config", "note"}` → a draft version |
| `POST /api/agents/{id}/versions/{v}/test` | OPERATOR | dry run on test cases and recent events → report |
| `POST /api/agents/{id}/versions/{v}/publish` | ADMIN | only after a passing report |
| `GET/POST /api/agents/{id}/cases` | VIEWER / OPERATOR | `{"runId","expected"?,"note"}` keeps a past run as a test case |
| `GET /api/approvals` | VIEWER | what waits for a person |
| `POST /api/approvals/{id}` | OPERATOR | `{"approve": true}`; recorded with the caller's name |
| `WS /ws/runs?key=` | VIEWER | `{"type":"run","run":{...}}` for every change of the tenant's runs |
| `/api/sandbox/...` | OPERATOR | sandbox only: open tickets, notes, sessions, visits, status, month close |

Event types: `ticket.created`, `ticket.status_changed`, `ticket.note_added`, `rmm.session_ended`,
`field.checked_out`, `erp.month_closed`.
