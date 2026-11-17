package mspagent.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** With RabbitMQ: the relay publishes to the partition queues and the consumers run the agents. CI runs this. */
@EnabledIfEnvironmentVariable(named = "MSP_TEST_RABBIT_HOST", matches = ".+")
class AmqpTest extends ServiceTest {

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry r) {
        r.add("msp.bus", () -> "amqp");
        r.add("spring.rabbitmq.host", () -> System.getenv("MSP_TEST_RABBIT_HOST"));
    }

    @Test
    void eventsTravelThroughTheBrokerToTheAgents() throws Exception {
        JsonNode t = post("/api/sandbox/tickets", OPERATOR, Map.of("customerId", "C1001", "subject", "Phishing",
                "body", "Eine Kollegin hat auf einen Phishing-Link geklickt."), 200);
        relay.relay();
        boolean done = false;
        for (int i = 0; i < 100 && !done; i++) {
            Thread.sleep(200);
            for (JsonNode r : get("/api/runs?agent=triage", VIEWER)) {
                done |= r.get("subject").asString().equals(t.get("ticketId").asString())
                        && r.get("status").asString().equals("DONE");
            }
        }
        assertTrue(done, "the consumer ran triage on the event");
    }
}
