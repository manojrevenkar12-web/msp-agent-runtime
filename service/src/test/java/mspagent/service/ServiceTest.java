package mspagent.service;

import java.util.Map;
import mspagent.core.engine.Json;
import mspagent.service.ingest.OutboxRelay;
import mspagent.service.runtime.Ticker;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/**
 * Base for tests against a real PostgreSQL ({@code MSP_TEST_DATABASE_URL}, a JDBC URL). The schema is rebuilt once per
 * Spring context; the relay and the ticker are driven by the tests instead of their schedules.
 */
@SpringBootTest(properties = {"msp.relay-millis=3600000", "msp.tick-millis=3600000", "msp.bus=inline",
    "msp.connectors.mode=sandbox", "msp.llm.mode=sandbox", "spring.flyway.clean-disabled=false"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(ServiceTest.CleanSchema.class)
@EnabledIfEnvironmentVariable(named = "MSP_TEST_DATABASE_URL", matches = ".+")
public abstract class ServiceTest {
    public static final String ADMIN = "demo-admin-key";
    public static final String OPERATOR = "demo-operator-key";
    public static final String VIEWER = "demo-viewer-key";
    public static final String OTHER_TENANT_ADMIN = "second-admin-key";

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("MSP_TEST_DATABASE_URL"));
        r.add("spring.datasource.username", () -> env("MSP_TEST_DATABASE_USER", "msp"));
        r.add("spring.datasource.password", () -> env("MSP_TEST_DATABASE_PASSWORD", ""));
    }

    static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null ? def : v;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean CLEANED = new java.util.concurrent.atomic.AtomicBoolean();

    /** The first Spring context of the test run starts from an empty schema; later contexts share it. */
    @TestConfiguration
    public static class CleanSchema {
        @Bean
        FlywayMigrationStrategy cleanFirst() {
            return (Flyway f) -> {
                if (CLEANED.compareAndSet(false, true)) {
                    f.clean();
                }
                f.migrate();
            };
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected OutboxRelay relay;

    @Autowired
    protected Ticker ticker;

    /** Lets the relay and the engine catch up, as their schedules would. */
    protected void settle() {
        for (int i = 0; i < 3; i++) {
            relay.relay();
            ticker.tick();
        }
    }

    protected JsonNode call(MockHttpServletRequestBuilder req, String key, int expected) throws Exception {
        if (key != null) {
            req.header("X-Api-Key", key);
        }
        MvcResult r = mvc.perform(req).andReturn();
        String body = r.getResponse().getContentAsString();
        if (r.getResponse().getStatus() != expected) {
            throw new AssertionError("expected " + expected + " but got " + r.getResponse().getStatus() + ": " + body);
        }
        return body.isEmpty() ? null : Json.MAPPER.readTree(body);
    }

    protected JsonNode get(String path, String key) throws Exception {
        return call(MockMvcRequestBuilders.get(path), key, 200);
    }

    protected JsonNode post(String path, String key, Object body, int expected) throws Exception {
        return call(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON)
                .content(Json.write(body == null ? Map.of() : body)), key, expected);
    }
}
