package mspagent.core.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mspagent.core.trace.Span;

/** The runtime store in memory, for the evaluation, tests and the console's demo. Thread-safe. */
public final class InMemoryRuntimeStore implements RuntimeStore {

    private final Set<String> deliveries = new HashSet<>();
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final Map<String, Map<String, StepRecord>> steps = new HashMap<>();
    private final Map<String, EffectRecord> effects = new HashMap<>();
    private final Map<String, Approval> approvals = new LinkedHashMap<>();
    private final Map<String, List<Span>> spans = new HashMap<>();
    private final Map<String, List<AgentVersion>> versions = new HashMap<>();

    private static String k(String tenant, String id) {
        return tenant + "\u0000" + id;
    }

    @Override
    public synchronized boolean firstDelivery(String tenant, String source, String eventId, Instant at) {
        return deliveries.add(tenant + "\u0000" + source + "\u0000" + eventId);
    }

    @Override
    public synchronized Run createRun(Run run) {
        return runs.computeIfAbsent(k(run.tenant(), run.id()), x -> run);
    }

    @Override
    public synchronized Optional<Run> run(String tenant, String runId) {
        return Optional.ofNullable(runs.get(k(tenant, runId)));
    }

    @Override
    public synchronized Optional<Run> claim(String tenant, String runId, String worker, Instant now, Instant until) {
        Run r = runs.get(k(tenant, runId));
        if (r == null || r.status().isFinal() || r.status() == RunStatus.WAITING_APPROVAL) {
            return Optional.empty();
        }
        if (r.status() == RunStatus.RUNNING && r.leaseUntil() != null && r.leaseUntil().isAfter(now)
                && !worker.equals(r.leaseOwner())) {
            return Optional.empty();
        }
        if (r.status() == RunStatus.RETRY && r.nextAttemptAt() != null && r.nextAttemptAt().isAfter(now)) {
            return Optional.empty();
        }
        Run c = r.claimed(worker, until, now);
        runs.put(k(tenant, runId), c);
        return Optional.of(c);
    }

    @Override
    public synchronized void saveRun(Run run) {
        runs.put(k(run.tenant(), run.id()), run);
    }

    @Override
    public synchronized List<Run> due(Instant now, int limit) {
        List<Run> out = new ArrayList<>();
        for (Run r : runs.values()) {
            boolean retry = r.status() == RunStatus.RETRY && !r.nextAttemptAt().isAfter(now);
            boolean lost = r.status() == RunStatus.RUNNING && r.leaseUntil() != null && !r.leaseUntil().isAfter(now);
            boolean pending = r.status() == RunStatus.PENDING;
            if (retry || lost || pending) {
                out.add(r);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    @Override
    public synchronized List<Run> runs(String tenant, RunQuery q) {
        return runs.values().stream()
                .filter(r -> r.tenant().equals(tenant))
                .filter(r -> q.agentId() == null || q.agentId().equals(r.agentId()))
                .filter(r -> q.status() == null || q.status() == r.status())
                .sorted(Comparator.comparing(Run::createdAt).reversed())
                .limit(q.limit())
                .toList();
    }

    @Override
    public synchronized Optional<StepRecord> step(String runId, String name) {
        return Optional.ofNullable(steps.getOrDefault(runId, Map.of()).get(name));
    }

    @Override
    public synchronized void putStep(StepRecord step) {
        steps.computeIfAbsent(step.runId(), x -> new LinkedHashMap<>()).putIfAbsent(step.name(), step);
    }

    @Override
    public synchronized List<StepRecord> steps(String runId) {
        return List.copyOf(steps.getOrDefault(runId, Map.of()).values());
    }

    @Override
    public synchronized Optional<EffectRecord> effect(String tenant, String key) {
        return Optional.ofNullable(effects.get(k(tenant, key)));
    }

    @Override
    public synchronized boolean beginEffect(EffectRecord effect) {
        return effects.putIfAbsent(k(effect.tenant(), effect.key()), effect) == null;
    }

    @Override
    public synchronized void completeEffect(String tenant, String key, Instant at) {
        EffectRecord e = effects.get(k(tenant, key));
        if (e != null) {
            effects.put(k(tenant, key), new EffectRecord(tenant, key, e.runId(), e.tool(), EffectRecord.State.DONE, at));
        }
    }

    @Override
    public synchronized Optional<Approval> approval(String tenant, String approvalId) {
        return Optional.ofNullable(approvals.get(approvalId)).filter(a -> a.tenant().equals(tenant));
    }

    @Override
    public synchronized Optional<Approval> approvalFor(String runId, String name) {
        return approvals.values().stream().filter(a -> a.runId().equals(runId) && a.name().equals(name)).findFirst();
    }

    @Override
    public synchronized void saveApproval(Approval approval) {
        approvals.put(approval.id(), approval);
    }

    @Override
    public synchronized List<Approval> pendingApprovals(String tenant) {
        return approvals.values().stream()
                .filter(a -> a.tenant().equals(tenant) && a.status() == Approval.Status.PENDING)
                .toList();
    }

    @Override
    public synchronized void addSpan(Span span) {
        spans.computeIfAbsent(k(span.tenant(), span.runId()), x -> new ArrayList<>()).add(span);
    }

    @Override
    public synchronized List<Span> spans(String tenant, String runId) {
        return List.copyOf(spans.getOrDefault(k(tenant, runId), List.of()));
    }

    @Override
    public synchronized Optional<AgentVersion> published(String tenant, String agentId) {
        return versions.getOrDefault(k(tenant, agentId), List.of()).stream()
                .filter(v -> v.status() == AgentVersion.Status.PUBLISHED)
                .findFirst();
    }

    @Override
    public synchronized List<AgentVersion> versions(String tenant, String agentId) {
        return List.copyOf(versions.getOrDefault(k(tenant, agentId), List.of()));
    }

    @Override
    public synchronized void saveVersion(AgentVersion v) {
        List<AgentVersion> list = versions.computeIfAbsent(k(v.tenant(), v.agentId()), x -> new ArrayList<>());
        if (v.status() == AgentVersion.Status.PUBLISHED) {
            list.replaceAll(o -> o.status() == AgentVersion.Status.PUBLISHED && o.version() != v.version()
                    ? o.with(AgentVersion.Status.RETIRED) : o);
        }
        list.removeIf(o -> o.version() == v.version());
        list.add(v);
        list.sort(Comparator.comparingInt(AgentVersion::version));
    }
}
