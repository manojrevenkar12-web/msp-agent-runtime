package mspagent.core.engine;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** How hard the engine retries and how long a worker holds a run. */
public record EngineSettings(int maxAttempts, Duration firstBackoff, Duration lease, Map<String, ModelPrice> prices) {

    public EngineSettings {
        prices = Map.copyOf(prices);
    }

    /** Reads {@code config/runtime.json}, the one file the evaluation and the service share. */
    public static EngineSettings fromJson(String json) {
        JsonNode n = Json.MAPPER.readTree(json);
        Map<String, ModelPrice> prices = new HashMap<>();
        n.get("prices").properties().forEach(e -> {
            if (!e.getKey().startsWith("_")) {
                prices.put(e.getKey(), new ModelPrice(e.getValue().get("inputCentsPerMillion").asLong(),
                        e.getValue().get("outputCentsPerMillion").asLong()));
            }
        });
        return new EngineSettings(n.get("maxAttempts").asInt(), Duration.ofSeconds(n.get("firstBackoffSeconds").asLong()),
                Duration.ofSeconds(n.get("leaseSeconds").asLong()), prices);
    }

    public Duration backoff(int attempt) {
        long factor = 1L << Math.min(10, Math.max(0, attempt - 1));
        return firstBackoff.multipliedBy(factor);
    }

    public ModelPrice price(String model) {
        ModelPrice p = prices.get(model);
        return p == null ? new ModelPrice(0, 0) : p;
    }
}
