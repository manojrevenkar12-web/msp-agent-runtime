package mspagent.core.engine;

import java.time.Instant;

/** A person's decision a run waits for. */
public record Approval(
        String id,
        String tenant,
        String runId,
        String agentId,
        String name,
        String reason,
        String payloadJson,
        Status status,
        String decidedBy,
        Instant createdAt,
        Instant decidedAt) {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    public Approval decided(boolean approve, String person, Instant at) {
        return new Approval(id, tenant, runId, agentId, name, reason, payloadJson,
                approve ? Status.APPROVED : Status.REJECTED, person, createdAt, at);
    }
}
