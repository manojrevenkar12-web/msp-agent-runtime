package mspagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/** Keys decide the tenant and the role; nothing crosses tenants. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MSP_TEST_DATABASE_URL", matches = ".+")
class SecurityTest extends ServiceTest {

    @Test
    void withoutAKeyTheApiAnswers401() throws Exception {
        call(MockMvcRequestBuilders.get("/api/runs"), null, 401);
        call(MockMvcRequestBuilders.get("/api/runs"), "not-a-key", 401);
    }

    @Test
    void aViewerReadsButCannotApproveOrConfigure() throws Exception {
        get("/api/agents", VIEWER);
        post("/api/approvals/x", VIEWER, Map.of("approve", true), 403);
        post("/api/agents/triage/versions", VIEWER, Map.of("config", Map.of()), 403);
        post("/api/agents/triage/versions", OPERATOR, Map.of("config", Map.of()), 403);
    }

    @Test
    void anotherTenantSeesNoneOfThisTenantsRuns() throws Exception {
        JsonNode t = post("/api/sandbox/tickets", OPERATOR, Map.of("customerId", "C1001", "subject", "VPN",
                "body", "VPN geht nicht, nur mein Rechner."), 200);
        settle();
        JsonNode mine = get("/api/runs", VIEWER);
        String runId = null;
        for (JsonNode r : mine) {
            if (r.get("subject").asString().equals(t.get("ticketId").asString())) {
                runId = r.get("id").asString();
            }
        }
        get("/api/runs/" + runId, VIEWER);
        call(MockMvcRequestBuilders.get("/api/runs/" + runId), OTHER_TENANT_ADMIN, 404);
        for (JsonNode r : get("/api/runs", OTHER_TENANT_ADMIN)) {
            assertEquals(false, r.get("id").asString().equals(runId));
        }
    }
}
