package mspagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** A ticket's life through the service: triage, time from a session and a note, a customer update, the invoice. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MSP_TEST_DATABASE_URL", matches = ".+")
class FlowTest extends ServiceTest {

    @Test
    void aTicketIsTriagedTimedUpdatedAndBilledOnce() throws Exception {
        JsonNode t = post("/api/sandbox/tickets", OPERATOR, Map.of("customerId", "C1003", "subject", "Fileserver down",
                "body", "Hallo, der Fileserver ist down, die ganze Firma kann nicht arbeiten.", "openedMinutesAgo", 120), 200);
        String ticket = t.get("ticketId").asString();
        settle();
        JsonNode runs = get("/api/runs?agent=triage", VIEWER);
        JsonNode triage = null;
        for (JsonNode r : runs) {
            if (r.get("subject").asString().equals(ticket)) {
                triage = r;
            }
        }
        assertEquals("DONE", triage.get("status").asString());
        JsonNode detail = get("/api/runs/" + triage.get("id").asString(), VIEWER);
        String decision = null;
        for (JsonNode s : detail.get("steps")) {
            if (s.get("name").asString().equals("decision")) {
                decision = s.get("json").asString();
            }
        }
        assertEquals("P1", mspagent.core.engine.Json.MAPPER.readTree(decision).get("priority").asString(), decision);
        assertTrue(detail.get("spans").size() >= 4, "run, steps, model call and the write are traced");

        post("/api/sandbox/tickets/" + ticket + "/remote-sessions", OPERATOR,
                Map.of("technicianId", "tech-1", "minutes", 50), 200);
        post("/api/sandbox/tickets/" + ticket + "/notes", OPERATOR,
                Map.of("technicianId", "tech-1", "text", "Fernwartung: Dienst neu gestartet, ca. 50 Min.", "internal", true),
                200);
        post("/api/sandbox/tickets/" + ticket + "/notes", OPERATOR,
                Map.of("technicianId", "tech-2", "text", "Telefonat mit Kunde, 10 Min.", "internal", false), 200);
        post("/api/sandbox/tickets/" + ticket + "/status", OPERATOR, Map.of("status", "RESOLVED"), 200);
        settle();
        String month = java.time.YearMonth.now(java.time.ZoneId.of("Europe/Berlin")).toString();
        post("/api/sandbox/month-close", OPERATOR, Map.of("month", month), 200);
        settle();
        JsonNode state = get("/api/sandbox/state", VIEWER);
        JsonNode inv = null;
        for (JsonNode i : state.get("invoices")) {
            if (i.get("customerId").asString().equals("C1003")) {
                inv = i;
            }
        }
        long labour = 0;
        for (JsonNode l : inv.get("lines")) {
            if (l.get("kind").asString().equals("LABOUR") && l.get("ticketId").asString().equals(ticket)) {
                labour += l.get("minutes").asLong();
            }
        }
        // 50 measured minutes (the note about the same session is not entered again) and a colleague's 10 minute call.
        assertEquals(60 + 15, labour);
        boolean updated = false;
        for (JsonNode u : state.get("updates")) {
            updated |= u.get("ticketId").asString().equals(ticket);
        }
        assertTrue(updated);

        // The month closes again (a resent event): no second invoice.
        int before = state.get("invoices").size();
        post("/api/sandbox/month-close", OPERATOR, Map.of("month", month), 200);
        settle();
        assertEquals(before, get("/api/sandbox/state", VIEWER).get("invoices").size());
    }
}
