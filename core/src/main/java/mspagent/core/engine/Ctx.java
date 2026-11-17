package mspagent.core.engine;

import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import mspagent.core.agent.AgentConfig;
import mspagent.core.event.Event;
import mspagent.core.port.LlmCall;
import mspagent.core.port.Tools;

/** What an agent may do during a run. */
public interface Ctx {

    String tenant();

    Event event();

    Run run();

    AgentConfig config();

    Tools tools();

    Instant now();

    /**
     * Runs {@code body} once per run and keeps its result. A resumed run gets the kept result back without running
     * the body again, so reads and model calls inside a step are not repeated.
     */
    <T> T step(String name, Class<T> type, Supplier<T> body);

    /**
     * Writes to an outside system at most once per tenant and key, across runs and workers. When a worker died between
     * the call and the record, {@code alreadyDone} asks the outside system whether the write landed.
     */
    void effect(String key, String tool, BooleanSupplier alreadyDone, Runnable call);

    /** Calls a model, tracing the call with its tokens and cost. Call it inside a step. */
    <T> LlmCall<T> llm(String task, Supplier<LlmCall<T>> call);

    /** Returns once a person approved; suspends the run until then; fails it if they rejected. */
    void approval(String name, String reason, Object payload);
}
