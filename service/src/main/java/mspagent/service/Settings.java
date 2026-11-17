package mspagent.service;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The service's settings ({@code msp.*} in application.yml, overridable by environment). Retry, lease and model prices
 * come from {@code config/runtime.json}, which the evaluation reads too.
 *
 * @param bus {@code amqp} (RabbitMQ) or {@code inline} (the relay runs the engine itself; for tests and one node)
 * @param partitions how many ordered queues events are spread over; events of one ticket share a queue
 */
@ConfigurationProperties("msp")
public record Settings(
        String bus,
        int partitions,
        String workerId,
        List<Tenant> tenants,
        Llm llm,
        Connectors connectors,
        Otlp otlp) {

    public record Tenant(String id, String name, String webhookSecret, List<Key> keys) {}

    /** An API key; roles are VIEWER, OPERATOR (approves, tests) and ADMIN (configures and publishes agents). */
    public record Key(String key, String role, String user) {}

    /** {@code sandbox} (rule-based, no network) or {@code openai} (any OpenAI-compatible endpoint). */
    public record Llm(String mode, String baseUrl, String model, String apiKey, Duration timeout) {}

    /** {@code sandbox} (in-memory systems) or {@code http} (the connector API in docs/connectors.md). */
    public record Connectors(String mode, String baseUrl, String token) {}

    /** Where traces go (an OTLP/HTTP endpoint such as Langfuse's); empty for none. */
    public record Otlp(String endpoint, String authorization, Duration every) {}

    public Tenant tenant(String id) {
        return tenants.stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
    }
}
