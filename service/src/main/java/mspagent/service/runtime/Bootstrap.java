package mspagent.service.runtime;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import mspagent.core.agent.Agent;
import mspagent.core.engine.AgentVersion;
import mspagent.core.engine.RuntimeStore;
import mspagent.service.Settings;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Gives every tenant a published version 1 of each agent with its tested defaults, once. */
@Component
public class Bootstrap {
    private final Settings settings;
    private final RuntimeStore store;
    private final List<Agent> agents;
    private final Clock clock;

    public Bootstrap(Settings settings, RuntimeStore store, List<Agent> agents, Clock clock) {
        this.settings = settings;
        this.store = store;
        this.agents = agents;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void publishDefaults() {
        for (Settings.Tenant t : settings.tenants()) {
            for (Agent a : agents) {
                if (store.versions(t.id(), a.id()).isEmpty()) {
                    store.saveVersion(new AgentVersion(t.id(), a.id(), 1, Map.of(), AgentVersion.Status.PUBLISHED,
                            "system", clock.instant(), "tested defaults"));
                }
            }
        }
    }
}
