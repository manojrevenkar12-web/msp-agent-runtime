package mspagent.core.engine;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import mspagent.core.agent.AgentConfig;
import mspagent.core.event.Event;
import mspagent.core.port.LlmCall;
import mspagent.core.port.LlmUnavailable;
import mspagent.core.port.Tools;
import mspagent.core.trace.Span;

/** The engine's {@link Ctx}: journaled steps, keyed effects, approvals and traces for one attempt of one run. */
final class RunContext implements Ctx {
    private final RuntimeStore store;
    private final Run run;
    private final AgentConfig config;
    private final Tools tools;
    private final Clock clock;
    private final EngineSettings settings;
    private final Faults faults;
    private final String traceId;
    private final String attemptSpanId;
    private int seq;
    private int spanSeq;
    private long costMicros;
    private int inputTokens;
    private int outputTokens;

    RunContext(RuntimeStore store, Run run, AgentConfig config, Tools tools, Clock clock, EngineSettings settings,
            Faults faults) {
        this.store = store;
        this.run = run;
        this.config = config;
        this.tools = tools;
        this.clock = clock;
        this.settings = settings;
        this.faults = faults;
        this.traceId = Hash.sha256(run.tenant() + "|" + run.id()).substring(0, 32);
        this.attemptSpanId = Hash.short64(run.id() + "|attempt|" + run.attempts());
        this.seq = store.steps(run.id()).size();
    }

    @Override
    public String tenant() {
        return run.tenant();
    }

    @Override
    public Event event() {
        return run.event();
    }

    @Override
    public Run run() {
        return run;
    }

    @Override
    public AgentConfig config() {
        return config;
    }

    @Override
    public Tools tools() {
        return tools;
    }

    @Override
    public Instant now() {
        return clock.instant();
    }

    @Override
    public <T> T step(String name, Class<T> type, Supplier<T> body) {
        Optional<StepRecord> kept = store.step(run.id(), name);
        if (kept.isPresent()) {
            return Json.read(kept.get().json(), type);
        }
        Instant t0 = now();
        T value = body.get();
        store.putStep(new StepRecord(run.id(), ++seq, name, Json.write(value), now()));
        span("step " + name, t0, Map.of("msp.step", name), false);
        faults.at("after-step", run.id(), name);
        return value;
    }

    @Override
    public void effect(String key, String tool, BooleanSupplier alreadyDone, Runnable call) {
        if (run.dryRun()) {
            step("plan:" + key, String.class, () -> tool);
            return;
        }
        Instant t0 = now();
        EffectRecord mine = new EffectRecord(run.tenant(), key, run.id(), tool, EffectRecord.State.STARTED, t0);
        if (!store.beginEffect(mine)) {
            EffectRecord e = store.effect(run.tenant(), key).orElseThrow();
            if (e.state() == EffectRecord.State.DONE) {
                return;
            }
            if (alreadyDone.getAsBoolean()) {
                store.completeEffect(run.tenant(), key, now());
                return;
            }
            if (!e.runId().equals(run.id())) {
                throw new EffectInFlight(key);
            }
            // This run started the write, died before recording it, and the outside system has no trace of it.
        }
        call.run();
        faults.at("after-call", run.id(), key);
        store.completeEffect(run.tenant(), key, now());
        span("tool " + tool, t0, Map.of("msp.tool", tool, "msp.effect.key", key), false);
    }

    @Override
    public <T> LlmCall<T> llm(String task, Supplier<LlmCall<T>> call) {
        Instant t0 = now();
        LlmCall<T> r;
        try {
            r = call.get();
        } catch (LlmUnavailable e) {
            span("chat " + task, t0, Map.of("gen_ai.operation.name", "chat", "msp.task", task,
                    "error.type", "LlmUnavailable"), true);
            throw e;
        }
        long cost = settings.price(r.model()).costMicros(r.inputTokens(), r.outputTokens());
        costMicros += cost;
        inputTokens += r.inputTokens();
        outputTokens += r.outputTokens();
        Map<String, String> a = new HashMap<>();
        a.put("gen_ai.operation.name", "chat");
        a.put("gen_ai.request.model", r.model());
        a.put("gen_ai.usage.input_tokens", Integer.toString(r.inputTokens()));
        a.put("gen_ai.usage.output_tokens", Integer.toString(r.outputTokens()));
        a.put("msp.cost_micros", Long.toString(cost));
        a.put("msp.task", task);
        span("chat " + task, t0, a, false);
        return r;
    }

    @Override
    public void approval(String name, String reason, Object payload) {
        if (run.dryRun()) {
            step("approval:" + name, String.class, () -> reason);
            return;
        }
        Optional<Approval> a = store.approvalFor(run.id(), name);
        if (a.isEmpty()) {
            Approval created = new Approval(Hash.short64(run.id() + "|" + name), run.tenant(), run.id(), run.agentId(),
                    name, reason, Json.write(payload), Approval.Status.PENDING, null, now(), null);
            store.saveApproval(created);
            throw new Suspended(created.id());
        }
        switch (a.get().status()) {
            case PENDING -> throw new Suspended(a.get().id());
            case REJECTED -> throw new Rejected(name + " rejected by " + a.get().decidedBy());
            case APPROVED -> {
                // go on
            }
        }
    }

    void endAttempt(Instant started, RunStatus status, String error) {
        Map<String, String> a = new HashMap<>();
        a.put("msp.agent", run.agentId());
        a.put("msp.agent.version", Integer.toString(run.agentVersion()));
        a.put("msp.event.type", run.event().type());
        a.put("msp.attempt", Integer.toString(run.attempts()));
        a.put("msp.status", status.name());
        a.put("msp.cost_micros", Long.toString(costMicros));
        a.put("gen_ai.usage.input_tokens", Integer.toString(inputTokens));
        a.put("gen_ai.usage.output_tokens", Integer.toString(outputTokens));
        if (error != null) {
            a.put("error.type", error);
        }
        store.addSpan(new Span(traceId, attemptSpanId, null, run.tenant(), run.id(), "run " + run.agentId(), started,
                now(), a, status == RunStatus.DEAD || status == RunStatus.RETRY || status == RunStatus.FAILED));
    }

    private void span(String name, Instant start, Map<String, String> attrs, boolean error) {
        String id = Hash.short64(run.id() + "|" + run.attempts() + "|" + (++spanSeq));
        store.addSpan(new Span(traceId, id, attemptSpanId, run.tenant(), run.id(), name, start, now(), attrs, error));
    }
}
