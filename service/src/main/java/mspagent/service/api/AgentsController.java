package mspagent.service.api;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import mspagent.core.agent.Agent;
import mspagent.core.agent.AgentConfig;
import mspagent.core.engine.AgentVersion;
import mspagent.core.engine.RuntimeStore;
import mspagent.core.view.Views;
import mspagent.service.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Agents and their versions: configure (ADMIN), test (OPERATOR), publish (ADMIN, only after the cases pass). */
@RestController
@RequestMapping("/api/agents")
public class AgentsController {
    private final List<Agent> agents;
    private final RuntimeStore store;
    private final TestBench bench;
    private final Clock clock;

    public AgentsController(List<Agent> agents, RuntimeStore store, TestBench bench, Clock clock) {
        this.agents = agents;
        this.store = store;
        this.bench = bench;
        this.clock = clock;
    }

    Agent agent(String id) {
        return agents.stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("no agent " + id));
    }

    AgentVersion version(String tenant, String agentId, int v) {
        return store.versions(tenant, agentId).stream().filter(x -> x.version() == v).findFirst()
                .orElseThrow(() -> new NoSuchElementException("no version " + v + " of " + agentId));
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        Caller c = Caller.current();
        return agents.stream().map(a -> Views.agent(a, store.versions(c.tenant(), a.id()))).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return Views.agent(agent(id), store.versions(Caller.current().tenant(), id));
    }

    public record NewVersion(Map<String, Object> config, String note) {}

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> draft(@PathVariable String id, @RequestBody NewVersion body) {
        Caller c = Caller.current();
        Agent a = agent(id);
        Map<String, Object> cfg = body.config() == null ? Map.of() : body.config();
        List<String> errors = AgentConfig.validate(a.params(), cfg);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        int next = store.versions(c.tenant(), id).stream().mapToInt(AgentVersion::version).max().orElse(0) + 1;
        store.saveVersion(new AgentVersion(c.tenant(), id, next, cfg, AgentVersion.Status.DRAFT, c.user(),
                clock.instant(), body.note()));
        return Views.agent(a, store.versions(c.tenant(), id));
    }

    @PostMapping("/{id}/versions/{v}/test")
    public Map<String, Object> test(@PathVariable String id, @PathVariable int v) {
        Caller c = Caller.current();
        return bench.test(c.tenant(), agent(id), version(c.tenant(), id, v));
    }

    @GetMapping("/{id}/versions/{v}/test")
    public Map<String, Object> lastTest(@PathVariable String id, @PathVariable int v) {
        Caller c = Caller.current();
        return bench.lastReport(c.tenant(), id, v)
                .orElseThrow(() -> new NoSuchElementException("version " + v + " has not been tested"));
    }

    @PostMapping("/{id}/versions/{v}/publish")
    public Map<String, Object> publish(@PathVariable String id, @PathVariable int v) {
        Caller c = Caller.current();
        AgentVersion ver = version(c.tenant(), id, v);
        Map<String, Object> report = bench.lastReport(c.tenant(), id, v)
                .orElseThrow(() -> new IllegalStateException("test version " + v + " before publishing it"));
        if (!Boolean.TRUE.equals(report.get("passed"))) {
            throw new IllegalStateException("version " + v + " failed its test cases");
        }
        store.saveVersion(ver.with(AgentVersion.Status.PUBLISHED));
        return Views.agent(agent(id), store.versions(c.tenant(), id));
    }

    @GetMapping("/{id}/cases")
    public List<TestBench.TestCase> cases(@PathVariable String id) {
        agent(id);
        return bench.cases(Caller.current().tenant(), id);
    }

    public record NewCase(String runId, Map<String, Object> expected, String note) {}

    @PostMapping("/{id}/cases")
    @ResponseStatus(HttpStatus.CREATED)
    public TestBench.TestCase addCase(@PathVariable String id, @RequestBody NewCase body) {
        return bench.addCase(Caller.current().tenant(), agent(id), body.runId(), body.expected(), body.note());
    }
}
