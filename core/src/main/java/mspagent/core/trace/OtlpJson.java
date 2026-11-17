package mspagent.core.trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mspagent.core.engine.Json;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spans as an OTLP/HTTP JSON request body ({@code POST /v1/traces}). Langfuse accepts the same body at
 * {@code /api/public/otel/v1/traces}, so the runtime's traces show up there with tokens and cost per model call.
 */
public final class OtlpJson {
    private OtlpJson() {}

    public static String traces(String serviceName, List<Span> spans) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ObjectNode rs = root.putArray("resourceSpans").addObject();
        ArrayNode ra = rs.putObject("resource").putArray("attributes");
        attr(ra, "service.name", serviceName);
        ObjectNode ss = rs.putArray("scopeSpans").addObject();
        ss.putObject("scope").put("name", "msp-agent-runtime");
        ArrayNode out = ss.putArray("spans");
        for (Span s : spans) {
            ObjectNode o = out.addObject();
            o.put("traceId", s.traceId());
            o.put("spanId", s.spanId());
            if (s.parentId() != null) {
                o.put("parentSpanId", s.parentId());
            }
            o.put("name", s.name());
            o.put("kind", s.parentId() == null ? 2 : 1);
            o.put("startTimeUnixNano", nanos(s.start()));
            o.put("endTimeUnixNano", nanos(s.end()));
            ArrayNode a = o.putArray("attributes");
            attr(a, "msp.tenant", s.tenant());
            attr(a, "msp.run_id", s.runId());
            for (Map.Entry<String, String> e : new TreeMap<>(s.attributes()).entrySet()) {
                if (e.getKey().startsWith("gen_ai.usage.") || e.getKey().endsWith("_micros")) {
                    ObjectNode kv = a.addObject();
                    kv.put("key", e.getKey());
                    kv.putObject("value").put("intValue", e.getValue());
                } else {
                    attr(a, e.getKey(), e.getValue());
                }
            }
            o.putObject("status").put("code", s.error() ? 2 : 1);
        }
        return Json.write(root);
    }

    private static void attr(ArrayNode a, String k, String v) {
        ObjectNode kv = a.addObject();
        kv.put("key", k);
        kv.putObject("value").put("stringValue", v);
    }

    private static String nanos(Instant t) {
        return Long.toString(t.getEpochSecond() * 1_000_000_000L + t.getNano());
    }
}
