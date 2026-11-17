package mspagent.service.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import mspagent.core.engine.Json;
import mspagent.core.engine.RuntimeStore;
import mspagent.service.Settings;
import mspagent.service.live.RunSocket;
import mspagent.service.security.Caller;
import mspagent.service.store.SpanQueries;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The console's first screen: what the tenant's agents did in the last day, and what the evaluation measured. */
@RestController
public class OverviewController {
    private final JdbcClient jdbc;
    private final RuntimeStore store;
    private final SpanQueries spans;
    private final Settings settings;
    private final RunSocket socket;
    private final Clock clock;

    public OverviewController(JdbcClient jdbc, RuntimeStore store, SpanQueries spans, Settings settings,
            RunSocket socket, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.spans = spans;
        this.settings = settings;
        this.socket = socket;
        this.clock = clock;
    }

    @GetMapping("/api/overview")
    public Map<String, Object> overview() throws IOException {
        Caller c = Caller.current();
        Timestamp since = Timestamp.from(clock.instant().minus(Duration.ofDays(1)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant", c.tenant());
        m.put("tenantName", settings.tenant(c.tenant()).name());
        m.put("user", c.user());
        m.put("role", c.role());
        Map<String, Map<String, Long>> byAgent = new LinkedHashMap<>();
        jdbc.sql("select agent_id, status, count(*) as n from runs where tenant = :t and created_at >= :s "
                        + "and dry_run = false group by agent_id, status order by agent_id, status")
                .param("t", c.tenant()).param("s", since)
                .query(rs -> {
                    byAgent.computeIfAbsent(rs.getString("agent_id"), x -> new LinkedHashMap<>())
                            .put(rs.getString("status"), rs.getLong("n"));
                });
        m.put("runsLastDay", byAgent);
        m.put("waitingApprovals", store.pendingApprovals(c.tenant()).size());
        m.put("retriedLastDay", jdbc.sql("select count(*) from runs where tenant = :t and created_at >= :s "
                        + "and attempts > 1 and dry_run = false")
                .param("t", c.tenant()).param("s", since).query(Long.class).single());
        m.put("usageLastDay", spans.usageSince(c.tenant(), clock.instant().minus(Duration.ofDays(1))));
        m.put("latencyLastDay", jdbc.sql("""
                select coalesce(percentile_cont(0.5) within group (order by extract(epoch from
                         updated_at - (event->>'occurredAt')::timestamptz)), 0) as p50,
                       coalesce(percentile_cont(0.95) within group (order by extract(epoch from
                         updated_at - (event->>'occurredAt')::timestamptz)), 0) as p95
                  from runs where tenant = :t and status = 'DONE' and created_at >= :s and dry_run = false""")
                .param("t", c.tenant()).param("s", since)
                .query((rs, n) -> Map.of("p50Seconds", rs.getDouble("p50"), "p95Seconds", rs.getDouble("p95")))
                .single());
        m.put("liveConsoles", socket.open(c.tenant()));
        ClassPathResource dev = new ClassPathResource("reports/dev.json");
        if (dev.exists()) {
            var n = Json.MAPPER.readTree(dev.getContentAsString(StandardCharsets.UTF_8));
            m.put("evaluation", n.get("designs"));
            m.put("evaluationSeeds", n.get("seeds"));
        }
        return m;
    }
}
