package mspagent.service.api;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import mspagent.core.agent.Agent;
import mspagent.core.engine.AgentVersion;
import mspagent.core.engine.Engine;
import mspagent.core.engine.EngineSettings;
import mspagent.core.engine.InMemoryRuntimeStore;
import mspagent.core.engine.Json;
import mspagent.core.engine.Run;
import mspagent.core.engine.RunStatus;
import mspagent.core.engine.RuntimeStore;
import mspagent.core.engine.StepRecord;
import mspagent.core.event.Event;
import mspagent.core.port.Tools;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * Tests an agent version before it is published, without writing anywhere: the version runs dry on the tenant's test
 * cases (each must decide what the case expects) and on the agent's latest real events (the report shows every
 * decision that would change). A version is published only after its cases pass.
 */
@Component
public class TestBench {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final RuntimeStore store;
    private final JdbcClient jdbc;
    private final Tools tools;
    private final Clock clock;
    private final EngineSettings settings;

    public TestBench(RuntimeStore store, JdbcClient jdbc, Tools tools, Clock clock, EngineSettings settings) {
        this.store = store;
        this.jdbc = jdbc;
        this.tools = tools;
        this.clock = clock;
        this.settings = settings;
    }

    public record TestCase(String id, Event event, Map<String, Object> expected, String note) {}

    public List<TestCase> cases(String tenant, String agentId) {
        return jdbc.sql("select id, event::text as event, expected::text as expected, note from test_cases "
                        + "where tenant = :t and agent_id = :a order by id")
                .param("t", tenant).param("a", agentId)
                .query((rs, n) -> new TestCase(rs.getString("id"), Json.read(rs.getString("event"), Event.class),
                        Json.MAPPER.readValue(rs.getString("expected"), MAP), rs.getString("note")))
                .list();
    }

    /**
     * Saves what a past run decided as a case every future version must reproduce: the fields given, or else the
     * agent's tested fields of the run's decision.
     */
    public TestCase addCase(String tenant, Agent agent, String runId, Map<String, Object> expected, String note) {
        String agentId = agent.id();
        Run r = store.run(tenant, runId).orElseThrow(() -> new java.util.NoSuchElementException("no run " + runId));
        if (!r.agentId().equals(agentId)) {
            throw new IllegalArgumentException("run " + runId + " is not a run of " + agentId);
        }
        Map<String, Object> exp = expected;
        if (exp == null || exp.isEmpty()) {
            Map<String, Object> d = decision(store.steps(r.id()))
                    .orElseThrow(() -> new IllegalArgumentException("run has no decision"));
            exp = new LinkedHashMap<>();
            for (String f : agent.testedFields().isEmpty() ? d.keySet() : agent.testedFields()) {
                exp.put(f, d.get(f));
            }
        }
        TestCase c = new TestCase(UUID.randomUUID().toString(), r.event(), exp, note);
        jdbc.sql("insert into test_cases (tenant, agent_id, id, event, expected, note) "
                        + "values (:t, :a, :id, cast(:e as jsonb), cast(:x as jsonb), :n)")
                .param("t", tenant).param("a", agentId).param("id", c.id()).param("e", Json.write(c.event()))
                .param("x", Json.write(c.expected())).param("n", note).update();
        return c;
    }

    static Optional<Map<String, Object>> decision(List<StepRecord> steps) {
        return steps.stream().filter(s -> s.name().equals("decision")).findFirst()
                .map(s -> Json.MAPPER.readValue(s.json(), MAP));
    }

    private Map<String, Object> dry(Agent agent, AgentVersion v, Event e) {
        InMemoryRuntimeStore scratch = new InMemoryRuntimeStore();
        Map<String, Object> out = new LinkedHashMap<>();
        Run r;
        try {
            r = Engine.dryRun(agent, v, e, tools, clock, settings, scratch);
        } catch (RuntimeException ex) {
            out.put("status", "ERROR");
            out.put("error", ex.getMessage());
            return out;
        }
        out.put("status", r.status().name());
        out.put("error", r.error());
        List<StepRecord> steps = scratch.steps(r.id());
        out.put("decision", decision(steps).orElse(null));
        out.put("plannedWrites", steps.stream().filter(s -> s.name().startsWith("plan:"))
                .map(s -> s.name().substring(5)).toList());
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> test(String tenant, Agent agent, AgentVersion v) {
        List<Map<String, Object>> caseResults = new ArrayList<>();
        boolean allPassed = true;
        for (TestCase c : cases(tenant, agent.id())) {
            Map<String, Object> d = dry(agent, v, c.event());
            Map<String, Object> decision = (Map<String, Object>) d.get("decision");
            List<Map<String, Object>> mismatches = new ArrayList<>();
            for (Map.Entry<String, Object> x : c.expected().entrySet()) {
                Object actual = decision == null ? null : decision.get(x.getKey());
                if (!Objects.equals(Json.write(x.getValue()), Json.write(actual))) {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("field", x.getKey());
                    mm.put("expected", x.getValue());
                    mm.put("actual", actual);
                    mismatches.add(mm);
                }
            }
            boolean passed = RunStatus.DONE.name().equals(d.get("status")) && mismatches.isEmpty();
            allPassed &= passed;
            Map<String, Object> cr = new LinkedHashMap<>();
            cr.put("id", c.id());
            cr.put("note", c.note());
            cr.put("eventId", c.event().id());
            cr.put("passed", passed);
            cr.put("status", d.get("status"));
            cr.put("mismatches", mismatches);
            caseResults.add(cr);
        }
        List<Map<String, Object>> changes = new ArrayList<>();
        List<Run> recent = store.runs(tenant, new RuntimeStore.RunQuery(agent.id(), RunStatus.DONE, 20));
        for (Run r : recent) {
            Map<String, Object> before = decision(store.steps(r.id())).orElse(Map.of());
            Map<String, Object> d = dry(agent, v, r.event());
            Map<String, Object> after = d.get("decision") == null ? Map.of() : (Map<String, Object>) d.get("decision");
            List<Map<String, Object>> fields = new ArrayList<>();
            for (String k : agent.testedFields().isEmpty() ? before.keySet() : agent.testedFields()) {
                if (!Objects.equals(Json.write(before.get(k)), Json.write(after.get(k)))) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("field", k);
                    f.put("before", before.get(k));
                    f.put("after", after.get(k));
                    fields.add(f);
                }
            }
            if (!fields.isEmpty()) {
                Map<String, Object> ch = new LinkedHashMap<>();
                ch.put("runId", r.id());
                ch.put("subject", r.event().data().getOrDefault("ticketId", r.event().data().getOrDefault("customerId", "")));
                ch.put("fields", fields);
                changes.add(ch);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("agentId", agent.id());
        report.put("version", v.version());
        report.put("at", clock.instant().toString());
        report.put("cases", caseResults);
        report.put("replayed", recent.size());
        report.put("changes", changes);
        report.put("passed", allPassed);
        jdbc.sql("""
                insert into test_reports (tenant, agent_id, version, report, passed, at)
                values (:t, :a, :v, cast(:r as jsonb), :p, :at)
                on conflict (tenant, agent_id, version) do update set report = cast(:r as jsonb), passed = :p, at = :at""")
                .param("t", tenant).param("a", agent.id()).param("v", v.version()).param("r", Json.write(report))
                .param("p", allPassed).param("at", Timestamp.from(clock.instant())).update();
        return report;
    }

    public Optional<Map<String, Object>> lastReport(String tenant, String agentId, int version) {
        return jdbc.sql("select report::text as r from test_reports where tenant = :t and agent_id = :a and version = :v")
                .param("t", tenant).param("a", agentId).param("v", version)
                .query((rs, n) -> Json.MAPPER.readValue(rs.getString("r"), MAP)).optional();
    }
}
