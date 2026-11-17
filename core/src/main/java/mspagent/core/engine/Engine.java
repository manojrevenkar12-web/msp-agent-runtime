package mspagent.core.engine;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mspagent.core.agent.Agent;
import mspagent.core.agent.AgentConfig;
import mspagent.core.event.Event;
import mspagent.core.port.Tools;
import mspagent.core.route.Router;

/**
 * The execution engine. A bus consumer calls {@link #handle} for every delivery and acknowledges after it returns; a
 * scheduler calls {@link #tick} to resume runs that wait for a retry, lost their worker, or were approved.
 *
 * <p>Guarantees, each covered by a test: a redelivered event does not start a second run; a run that dies after any
 * step resumes from its kept steps; a write happens once per key even when a worker dies between the call and the
 * record; a run waits for a person without holding a worker.
 */
public final class Engine {
    private final RuntimeStore store;
    private final Router router;
    private final Map<String, Agent> agents = new HashMap<>();
    private final Tools tools;
    private final Clock clock;
    private final EngineSettings settings;
    private final Faults faults;
    private final RunListener listener;

    public Engine(RuntimeStore store, Router router, List<Agent> agents, Tools tools, Clock clock,
            EngineSettings settings, Faults faults, RunListener listener) {
        this.store = store;
        this.router = router;
        agents.forEach(a -> this.agents.put(a.id(), a));
        this.tools = tools;
        this.clock = clock;
        this.settings = settings;
        this.faults = faults;
        this.listener = listener;
    }

    /** What one delivery led to. */
    public record Outcome(boolean firstDelivery, int started, int resumed, int skipped) {}

    public Outcome handle(Event e, String worker) {
        Instant now = clock.instant();
        boolean first = store.firstDelivery(e.tenant(), e.source(), e.id(), now);
        int started = 0;
        int resumed = 0;
        int skipped = 0;
        for (String agentId : router.agentsFor(e)) {
            Optional<AgentVersion> v = store.published(e.tenant(), agentId);
            if (v.isEmpty() || !agents.containsKey(agentId)) {
                continue;
            }
            Run fresh = new Run(Run.idFor(e.tenant(), e.key(), agentId), e.tenant(), agentId, v.get().version(), e,
                    RunStatus.PENDING, 0, null, null, null, now, now, null, false);
            Run stored = store.createRun(fresh);
            if (stored.status().isFinal() || stored.status() == RunStatus.WAITING_APPROVAL) {
                skipped++;
                continue;
            }
            if (stored.attempts() == 0) {
                started++;
            } else {
                resumed++;
            }
            execute(stored, worker);
        }
        return new Outcome(first, started, resumed, skipped);
    }

    /** Resumes due runs; returns how many were executed. */
    public int tick(String worker, int limit) {
        int n = 0;
        for (Run r : store.due(clock.instant(), limit)) {
            if (execute(r, worker)) {
                n++;
            }
        }
        return n;
    }

    /** Records a person's decision and makes the waiting run due. */
    public Approval decide(String tenant, String approvalId, boolean approve, String person) {
        Approval a = store.approval(tenant, approvalId)
                .orElseThrow(() -> new IllegalArgumentException("no approval " + approvalId));
        if (a.status() != Approval.Status.PENDING) {
            return a;
        }
        Approval d = a.decided(approve, person, clock.instant());
        store.saveApproval(d);
        store.run(tenant, a.runId()).ifPresent(r -> {
            Run p = r.with(RunStatus.PENDING, clock.instant());
            store.saveRun(p);
            listener.changed(p);
        });
        return d;
    }

    /**
     * Runs an agent version on an event without writing anywhere: writes and approvals are kept as planned steps. The
     * console's test bench uses this on recorded events before a version is published.
     */
    public static Run dryRun(Agent agent, AgentVersion version, Event e, Tools tools, Clock clock,
            EngineSettings settings, RuntimeStore scratch) {
        Instant now = clock.instant();
        Run r = new Run(Run.idFor(e.tenant(), e.key() + "|dry|" + version.version(), agent.id()), e.tenant(),
                agent.id(), version.version(), e, RunStatus.PENDING, 0, null, null, null, now, now, null, true);
        scratch.saveVersion(version);
        scratch.createRun(r);
        Engine eng = new Engine(scratch, new Router(List.of()), List.of(agent), tools, clock, settings, Faults.NONE,
                RunListener.NONE);
        eng.execute(r, "test-bench");
        return scratch.run(e.tenant(), r.id()).orElseThrow();
    }

    boolean execute(Run r, String worker) {
        Instant now = clock.instant();
        Optional<Run> claimed = store.claim(r.tenant(), r.id(), worker, now, now.plus(settings.lease()));
        if (claimed.isEmpty()) {
            return false;
        }
        Run run = claimed.get();
        listener.changed(run);
        Agent agent = agents.get(run.agentId());
        Optional<AgentVersion> version = store.versions(run.tenant(), run.agentId()).stream()
                .filter(v -> v.version() == run.agentVersion())
                .findFirst();
        if (agent == null || version.isEmpty()) {
            Run failed = run.finished(RunStatus.FAILED, "no version " + run.agentVersion() + " of agent "
                    + run.agentId() + " for this tenant", clock.instant());
            store.saveRun(failed);
            listener.changed(failed);
            return true;
        }
        AgentConfig cfg = AgentConfig.of(agent.params(), version.get().config());
        RunContext ctx = new RunContext(store, run, cfg, tools, clock, settings, faults);
        Run end;
        String error = null;
        try {
            agent.run(ctx);
            end = run.finished(RunStatus.DONE, null, clock.instant());
        } catch (Suspended s) {
            end = run.finished(RunStatus.WAITING_APPROVAL, null, clock.instant());
        } catch (Rejected | PermanentFailure x) {
            error = x.getMessage();
            end = run.finished(RunStatus.FAILED, error, clock.instant());
        } catch (RuntimeException x) {
            error = x.getClass().getSimpleName() + ": " + x.getMessage();
            Instant t = clock.instant();
            end = run.attempts() >= settings.maxAttempts()
                    ? run.finished(RunStatus.DEAD, error, t)
                    : run.retryAt(t.plus(settings.backoff(run.attempts())), error, t);
        }
        ctx.endAttempt(now, end.status(), error);
        store.saveRun(end);
        listener.changed(end);
        return true;
    }
}
