# Repeatable tasks

- **Add an agent.** Implement `Agent` in `core/agent`: declare its event types, settings with limits and tested
  fields; read in `ctx.step`, write in `ctx.effect` with a key the outside system stores. Add it to `Agents.builtIn()`,
  give it a lane in the console's switchboard, and a design case in `eval` if it changes money or messages.
- **Add a connector.** Implement the ports in `core/port` for the system, or implement docs/connectors.md on its
  side. Writes must accept an idempotency key and answer `exists`.
- **Change a rule an agent applies.** Change it in `core`, add a unit test with the case that motivated it, run
  `:eval:evaluate :eval:readme :eval:demo`, and note that the held-out result no longer applies until new held-out
  months are run.
- **Use a real model.** `MSP_LLM_MODE=openai`, `MSP_LLM_BASE_URL`, `MSP_LLM_MODEL`, `MSP_LLM_API_KEY`; set its price
  in `config/runtime.json`.
- **Send traces to Langfuse.** `MSP_OTLP_ENDPOINT=https://cloud.langfuse.com/api/public/otel/v1/traces` and
  `MSP_OTLP_AUTHORIZATION="Basic base64(public:secret)"`.
