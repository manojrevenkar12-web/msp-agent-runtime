package mspagent.core.engine;

import java.time.Instant;
import java.util.Map;

/** A tenant's configuration of an agent. Only a published version receives events. */
public record AgentVersion(
        String tenant,
        String agentId,
        int version,
        Map<String, Object> config,
        Status status,
        String createdBy,
        Instant createdAt,
        String note) {

    public AgentVersion {
        config = Map.copyOf(config);
    }

    public enum Status {
        DRAFT,
        PUBLISHED,
        RETIRED
    }

    public AgentVersion with(Status s) {
        return new AgentVersion(tenant, agentId, version, config, s, createdBy, createdAt, note);
    }
}
