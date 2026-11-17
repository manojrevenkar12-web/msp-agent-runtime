package mspagent.core.engine;

import java.time.Instant;

/**
 * A write to an outside system under an idempotency key. STARTED without DONE means a worker died between the call and
 * the record; the next attempt asks the outside system whether the write landed before calling again.
 */
public record EffectRecord(String tenant, String key, String runId, String tool, State state, Instant at) {
    public enum State {
        STARTED,
        DONE
    }
}
