# AGENTS.md

How to work in this repository, for people and coding agents.

## Fast feedback

```bash
./gradlew :core:test                    # engine, agents, billing rules, store contract (seconds)
./gradlew :eval:test                    # the runtime never reads the truth; same luck; nothing written twice
MSP_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/msp ./gradlew :service:test   # needs PostgreSQL
./gradlew :eval:evaluate :eval:readme :eval:demo   # reports/dev.json, README results, console demo data
cd console && npx ng test --watch=false && npx ng build
```

CI runs the service tests on PostgreSQL with pgvector and on RabbitMQ, starts the jar and sends a signed webhook.

## Invariants (tests enforce them)

1. **The truth stays in the world.** `world/` holds what really happened; `core/` and `service/` never import it or
   read `config/world.json` (`theRuntimeNeverReadsTheTruthOrTheWorldsSettings`). The runtime sees only what the
   systems would show.
2. **Same luck for every design.** The world is generated once per seed and every random decision is keyed by what is
   decided, so the three designs meet the same month (`everyDesignMeetsTheSameMonthAndTheSameLuck`).
3. **The model reads and code decides.** Model output passes a code check before it changes a priority, a minute or a
   message; no model touches an amount (docs/adr/0002).
4. **Every write once.** Reads go through `ctx.step`, writes through `ctx.effect` with an idempotency key
   (`aWriteIsNotRepeatedWhenTheWorkerDiedBeforeRecordingIt`, `underFaultsTheRuntimeWritesNothingTwice`).
5. **Money is integer cents** (`BillingRules`).
6. **Every query is scoped to the caller's tenant** (`anotherTenantSeesNoneOfThisTenantsRuns`).
7. **Settings in config.** The simulation in `config/world.json`, retries, leases and prices in
   `config/runtime.json` (read by the evaluation and the service), the console's sizes and colours in
   `console/src/app/theme`.
8. **Generated reports.** `reports/dev.json`, the README's results block and `console/public/demo.json` come from
   `eval`; CI regenerates them and fails on a difference.

## The held-out rule

The held-out months use seeds the runtime was never run on and inputs the development months never had (listed in
`config/world.json`). Before the run, `./gradlew :eval:lock` pinned `core/src/main` and `config/runtime.json` in
`config/heldout.lock`; `:eval:heldout` refuses a changed runtime and refuses to run twice, and its result is published
as it came out. A test fails when the runtime changes after that: a change to the runtime needs new held-out seeds and
new unseen inputs before results are published again.
