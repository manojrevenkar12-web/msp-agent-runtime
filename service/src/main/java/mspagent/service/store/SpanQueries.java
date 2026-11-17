package mspagent.service.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mspagent.core.trace.Span;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Span reads for the exporter and the overview. */
@Component
public class SpanQueries {
    private final JdbcClient jdbc;

    public SpanQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(long id, Span span) {}

    public List<Row> unexported(int limit) {
        return jdbc.sql("select id, " + PostgresRuntimeStore.SPAN_COLS + " from spans where exported = false "
                        + "order by id limit :n")
                .param("n", limit)
                .query((rs, n) -> new Row(rs.getLong("id"), PostgresRuntimeStore.span(rs, n))).list();
    }

    public void markExported(List<Long> ids) {
        if (!ids.isEmpty()) {
            jdbc.sql("update spans set exported = true where id in (:ids)").param("ids", ids).update();
        }
    }

    /** Model tokens and cost of a tenant's runs since a time. */
    public Map<String, Object> usageSince(String tenant, Instant since) {
        return jdbc.sql("""
                select coalesce(sum((attributes->>'gen_ai.usage.input_tokens')::bigint), 0) as input_tokens,
                       coalesce(sum((attributes->>'gen_ai.usage.output_tokens')::bigint), 0) as output_tokens,
                       coalesce(sum((attributes->>'msp.cost_micros')::bigint), 0) as cost_micros,
                       count(*) as model_calls
                  from spans where tenant = :t and start_at >= :s and name like 'chat %' and error = false""")
                .param("t", tenant).param("s", Timestamp.from(since))
                .query((rs, n) -> Map.<String, Object>of("inputTokens", rs.getLong("input_tokens"),
                        "outputTokens", rs.getLong("output_tokens"), "costMicros", rs.getLong("cost_micros"),
                        "modelCalls", rs.getLong("model_calls")))
                .single();
    }
}
