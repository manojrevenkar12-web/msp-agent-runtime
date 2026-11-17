package mspagent.core.trace;

import java.time.Instant;
import java.util.Map;

/**
 * A traced unit of work: a run, a step, a model call or a tool call. Attribute names follow the OpenTelemetry GenAI
 * semantic conventions where one exists ({@code gen_ai.usage.input_tokens}), so Langfuse and other OTLP back ends
 * read them without mapping.
 */
public record Span(
        String traceId,
        String spanId,
        String parentId,
        String tenant,
        String runId,
        String name,
        Instant start,
        Instant end,
        Map<String, String> attributes,
        boolean error) {

    public Span {
        attributes = Map.copyOf(attributes);
    }
}
