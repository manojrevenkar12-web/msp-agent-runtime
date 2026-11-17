package mspagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** A version is configured within its limits, tested dry against cases and recent events, and only then published. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MSP_TEST_DATABASE_URL", matches = ".+")
class AgentVersionTest extends ServiceTest {

    int latest(JsonNode agent) {
        int v = 0;
        for (JsonNode x : agent.get("versions")) {
            v = Math.max(v, x.get("version").asInt());
        }
        return v;
    }

    @Test
    void aSettingOutsideItsLimitsIsRefused() throws Exception {
        JsonNode r = post("/api/agents/time-capture/versions", ADMIN,
                Map.of("config", Map.of("autoConfirmMinutes", 900)), 400);
        assertTrue(r.get("error").asString().contains("between 0 and 240"));
    }

    @Test
    void aVersionIsPublishedOnlyAfterItsCasesPass() throws Exception {
        JsonNode t = post("/api/sandbox/tickets", OPERATOR, Map.of("customerId", "C1002", "subject", "Drucker",
                "body", "Der Drucker im Lager druckt nicht, nur mein Rechner ist betroffen."), 200);
        settle();
        String runId = null;
        for (JsonNode r : get("/api/runs?agent=triage", VIEWER)) {
            if (r.get("subject").asString().equals(t.get("ticketId").asString())) {
                runId = r.get("id").asString();
            }
        }
        post("/api/agents/triage/cases", OPERATOR, Map.of("runId", runId, "note", "printer, one user"), 201);

        int v = latest(post("/api/agents/triage/versions", ADMIN,
                Map.of("config", Map.of("minNeighbours", 5), "note", "need more history"), 201));
        post("/api/agents/triage/versions/" + v + "/publish", ADMIN, null, 409);
        JsonNode report = post("/api/agents/triage/versions/" + v + "/test", OPERATOR, null, 200);
        assertTrue(report.get("passed").asBoolean(), report.toString());
        assertTrue(report.get("replayed").asInt() >= 1);
        JsonNode agent = post("/api/agents/triage/versions/" + v + "/publish", ADMIN, null, 200);
        assertEquals(v, agent.get("published").asInt());

        // A case whose expectation the version does not meet blocks the next version.
        post("/api/agents/triage/cases", OPERATOR, Map.of("runId", runId, "expected", Map.of("priority", "P1"),
                "note", "wrong on purpose"), 201);
        int w = latest(post("/api/agents/triage/versions", ADMIN, Map.of("config", Map.of()), 201));
        JsonNode failed = post("/api/agents/triage/versions/" + w + "/test", OPERATOR, null, 200);
        assertFalse(failed.get("passed").asBoolean());
        post("/api/agents/triage/versions/" + w + "/publish", ADMIN, null, 409);
    }
}
