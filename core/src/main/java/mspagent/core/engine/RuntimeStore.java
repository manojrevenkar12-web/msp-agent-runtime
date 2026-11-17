package mspagent.core.engine;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import mspagent.core.trace.Span;

/**
 * Everything the runtime keeps. The in-memory store runs the evaluation and the tests; the service keeps the same
 * contract on PostgreSQL. Both pass {@code RuntimeStoreContract}.
 */
public interface RuntimeStore {

    /** Records an event delivery; true the first time this tenant/source/id is seen. */
    boolean firstDelivery(String tenant, String source, String eventId, Instant at);

    /** Inserts the run unless one with its id exists; returns the stored run either way. */
    Run createRun(Run run);

    Optional<Run> run(String tenant, String runId);

    /**
     * Takes the run for a worker until {@code until}, unless another worker holds an unexpired lease or the run is
     * final or waiting for a person. Returns the claimed run.
     */
    Optional<Run> claim(String tenant, String runId, String worker, Instant now, Instant until);

    void saveRun(Run run);

    /** Runs whose retry time has come, or whose worker's lease ran out. */
    List<Run> due(Instant now, int limit);

    List<Run> runs(String tenant, RunQuery query);

    Optional<StepRecord> step(String runId, String name);

    void putStep(StepRecord step);

    List<StepRecord> steps(String runId);

    Optional<EffectRecord> effect(String tenant, String key);

    /** Records that a write is starting; false when a record for this tenant and key already exists. */
    boolean beginEffect(EffectRecord effect);

    void completeEffect(String tenant, String key, Instant at);

    Optional<Approval> approval(String tenant, String approvalId);

    Optional<Approval> approvalFor(String runId, String name);

    void saveApproval(Approval approval);

    List<Approval> pendingApprovals(String tenant);

    void addSpan(Span span);

    List<Span> spans(String tenant, String runId);

    Optional<AgentVersion> published(String tenant, String agentId);

    List<AgentVersion> versions(String tenant, String agentId);

    /** Stores a version; publishing one retires the tenant's previously published version of that agent. */
    void saveVersion(AgentVersion version);

    record RunQuery(String agentId, RunStatus status, int limit) {
        public static RunQuery latest(int limit) {
            return new RunQuery(null, null, limit);
        }
    }
}
