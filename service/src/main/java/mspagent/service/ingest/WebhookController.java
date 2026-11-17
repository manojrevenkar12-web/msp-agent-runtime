package mspagent.service.ingest;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import mspagent.core.engine.Json;
import mspagent.core.event.Event;
import mspagent.service.Settings;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Webhooks from a tenant's systems: {@code POST /hooks/{tenant}/{source}} with {@code {"id", "type", "occurredAt",
 * "data"}}, signed with the tenant's webhook secret. Answers 202 at once; the agents run behind the outbox.
 */
@RestController
public class WebhookController {
    private final Settings settings;
    private final Ingest ingest;
    private final Clock clock;

    public WebhookController(Settings settings, Ingest ingest, Clock clock) {
        this.settings = settings;
        this.ingest = ingest;
        this.clock = clock;
    }

    @PostMapping("/hooks/{tenant}/{source}")
    public ResponseEntity<Map<String, Object>> hook(@PathVariable String tenant, @PathVariable String source,
            @RequestHeader(value = "X-Msp-Signature", required = false) String signature, @RequestBody byte[] body) {
        Settings.Tenant t = settings.tenant(tenant);
        if (t == null || !Signatures.valid(t.webhookSecret(), body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "bad signature"));
        }
        JsonNode n;
        try {
            n = Json.MAPPER.readTree(body);
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "body is not JSON"));
        }
        if (!n.hasNonNull("id") || !n.hasNonNull("type")) {
            return ResponseEntity.badRequest().body(Map.of("error", "id and type are required"));
        }
        Map<String, String> data = new HashMap<>();
        n.path("data").properties().forEach(e -> data.put(e.getKey(), e.getValue().asString()));
        Instant at = n.hasNonNull("occurredAt") ? Instant.parse(n.get("occurredAt").asString()) : clock.instant();
        Event e = new Event(tenant, source, n.get("id").asString(), n.get("type").asString(), at, data);
        boolean first = ingest.accept(e);
        return ResponseEntity.accepted().body(Map.of("accepted", true, "duplicate", !first));
    }
}
