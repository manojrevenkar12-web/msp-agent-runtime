package mspagent.service.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mspagent.core.trace.OtlpJson;
import mspagent.core.trace.Span;
import mspagent.service.Settings;
import mspagent.service.store.SpanQueries;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends new spans to an OTLP/HTTP endpoint (Langfuse: {@code https://cloud.langfuse.com/api/public/otel/v1/traces}
 * with Basic auth) when {@code msp.otlp.endpoint} is set. A failed export is tried again with the next batch.
 */
@Component
public class OtlpExporter {
    private final Settings settings;
    private final SpanQueries spans;
    private final RestClient http = RestClient.create();
    public volatile String lastError;

    public OtlpExporter(Settings settings, SpanQueries spans) {
        this.settings = settings;
        this.spans = spans;
    }

    @Scheduled(fixedDelayString = "${msp.otlp.every:5s}")
    public void export() {
        String endpoint = settings.otlp() == null ? null : settings.otlp().endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return;
        }
        List<SpanQueries.Row> rows = spans.unexported(500);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, List<Span>> byTenant = new LinkedHashMap<>();
        rows.forEach(r -> byTenant.computeIfAbsent(r.span().tenant(), t -> new ArrayList<>()).add(r.span()));
        try {
            for (List<Span> batch : byTenant.values()) {
                RestClient.RequestBodySpec req = http.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON);
                if (settings.otlp().authorization() != null && !settings.otlp().authorization().isBlank()) {
                    req = req.header("Authorization", settings.otlp().authorization());
                }
                req.body(OtlpJson.traces("msp-agent-runtime", batch)).retrieve().toBodilessEntity();
            }
            spans.markExported(rows.stream().map(SpanQueries.Row::id).toList());
            lastError = null;
        } catch (RestClientException e) {
            lastError = e.getMessage();
        }
    }
}
