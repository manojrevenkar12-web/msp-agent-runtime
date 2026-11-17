package mspagent.core.engine;

import java.time.Instant;
import mspagent.core.event.Event;

/**
 * One agent handling one event. The id is derived from the tenant, the event and the agent, so a redelivered event
 * finds the run it already started instead of starting a second one.
 */
public record Run(
        String id,
        String tenant,
        String agentId,
        int agentVersion,
        Event event,
        RunStatus status,
        int attempts,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseUntil,
        Instant createdAt,
        Instant updatedAt,
        String error,
        boolean dryRun) {

    public static String idFor(String tenant, String eventKey, String agentId) {
        return Hash.short64(tenant + "|" + eventKey + "|" + agentId);
    }

    public Run with(RunStatus s, Instant now) {
        return new Run(id, tenant, agentId, agentVersion, event, s, attempts, nextAttemptAt, leaseOwner, leaseUntil,
                createdAt, now, error, dryRun);
    }

    public Run retryAt(Instant at, String err, Instant now) {
        return new Run(id, tenant, agentId, agentVersion, event, RunStatus.RETRY, attempts, at, null, null, createdAt,
                now, err, dryRun);
    }

    public Run finished(RunStatus s, String err, Instant now) {
        return new Run(id, tenant, agentId, agentVersion, event, s, attempts, null, null, null, createdAt, now, err,
                dryRun);
    }

    public Run claimed(String worker, Instant until, Instant now) {
        return new Run(id, tenant, agentId, agentVersion, event, RunStatus.RUNNING, attempts + 1, null, worker, until,
                createdAt, now, error, dryRun);
    }
}
