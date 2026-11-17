package mspagent.eval;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import mspagent.core.agent.Agent;
import mspagent.core.agent.Agents;
import mspagent.core.engine.AgentVersion;
import mspagent.core.engine.Approval;
import mspagent.core.engine.Engine;
import mspagent.core.engine.EngineSettings;
import mspagent.core.engine.Faults;
import mspagent.core.engine.InMemoryRuntimeStore;
import mspagent.core.engine.Run;
import mspagent.core.engine.RunStatus;
import mspagent.core.engine.SimulatedCrash;
import mspagent.core.event.Event;
import mspagent.core.route.Router;
import mspagent.world.Rand;
import mspagent.world.SimLlm;
import mspagent.world.World;

/** This runtime: the four agents with their default settings on the engine, under the same faults. */
final class PlatformDesign implements Timeline.Consumer {
    private final World w;
    final InMemoryRuntimeStore store = new InMemoryRuntimeStore();
    private final EngineSettings settings;
    private Engine engine;
    private Timeline timeline;
    private final Map<String, Integer> faultCounts = new HashMap<>();
    int crashes;
    int approvals;
    boolean officeApproves = true;

    PlatformDesign(World w, EngineSettings settings) {
        this.w = w;
        this.settings = settings;
        for (Agent a : Agents.builtIn()) {
            store.saveVersion(new AgentVersion(w.tenant, a.id(), 1, Map.of(), AgentVersion.Status.PUBLISHED,
                    "evaluation", w.monthStart, "defaults"));
        }
    }

    void bind(Timeline t, SimLlm llm) {
        this.timeline = t;
        Faults faults = (point, runId, detail) -> {
            int n = faultCounts.merge(runId + "|" + point + "|" + detail, 1, Integer::sum);
            if (Rand.of(w.seed, "crash|" + runId + "|" + point + "|" + detail + "|" + n)
                    .chance(w.settings.delivery().crash() / 3)) {
                crashes++;
                throw new SimulatedCrash(point + " " + detail);
            }
        };
        engine = new Engine(store, Router.fromAgents(Agents.builtIn()), Agents.builtIn(), t.sys.tools(llm), t.clock,
                settings, faults, this::changed);
    }

    private void changed(Run r) {
        if (r.status() == RunStatus.RETRY && r.nextAttemptAt() != null) {
            timeline.at(r.nextAttemptAt(), this::tick);
        } else if (r.status() == RunStatus.WAITING_APPROVAL && officeApproves) {
            Instant office = w.monthEnd.plusSeconds(86_400 + 3 * 3600);
            timeline.at(office.isAfter(timeline.clock.instant()) ? office : timeline.clock.instant().plusSeconds(3600),
                    this::approveAll);
        }
    }

    private void approveAll() {
        for (Approval a : store.pendingApprovals(w.tenant)) {
            approvals++;
            engine.decide(w.tenant, a.id(), true, "office@" + w.tenant);
        }
        tick();
    }

    private void tick() {
        try {
            engine.tick("worker-2", 1000);
        } catch (SimulatedCrash c) {
            timeline.at(timeline.clock.instant().plus(settings.lease()).plusSeconds(60), this::tick);
        }
    }

    @Override
    public void deliver(Event e, Timeline t) {
        try {
            engine.handle(e, "worker-1");
        } catch (SimulatedCrash c) {
            // The broker redelivers an unacknowledged message after the dead consumer's channel closes.
            t.schedule(this, e, t.clock.instant().plus(settings.lease()).plusSeconds(60));
        }
    }
}
