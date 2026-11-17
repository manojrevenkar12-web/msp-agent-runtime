package mspagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import mspagent.service.ingest.Signatures;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/** Webhooks are signed, and a webhook sent twice is accepted once. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MSP_TEST_DATABASE_URL", matches = ".+")
class WebhookTest extends ServiceTest {
    static final String SECRET = "demo-webhook-secret";

    JsonNode hook(String tenant, String body, String signature, int expected) throws Exception {
        var req = MockMvcRequestBuilders.post("/hooks/" + tenant + "/tickets").contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (signature != null) {
            req.header("X-Msp-Signature", signature);
        }
        return call(req, null, expected);
    }

    @Test
    void anUnsignedOrBadlySignedWebhookIsRefused() throws Exception {
        String body = "{\"id\":\"w-1\",\"type\":\"ticket.created\",\"data\":{\"ticketId\":\"T-x\"}}";
        hook("systemhaus-nord", body, null, 401);
        hook("systemhaus-nord", body, Signatures.sign("wrong", body.getBytes(StandardCharsets.UTF_8)), 401);
        hook("no-such-tenant", body, Signatures.sign(SECRET, body.getBytes(StandardCharsets.UTF_8)), 401);
    }

    @Test
    void aWebhookSentTwiceIsAcceptedOnce() throws Exception {
        String body = "{\"id\":\"w-dup-" + System.nanoTime() + "\",\"type\":\"erp.unknown_event\",\"data\":{}}";
        String sig = Signatures.sign(SECRET, body.getBytes(StandardCharsets.UTF_8));
        assertFalse(hook("systemhaus-nord", body, sig, 202).get("duplicate").asBoolean());
        assertTrue(hook("systemhaus-nord", body, sig, 202).get("duplicate").asBoolean());
    }

    @Test
    void aBodyWithoutIdOrTypeIsABadRequest() throws Exception {
        String body = "{\"type\":\"ticket.created\"}";
        JsonNode r = hook("systemhaus-nord", body, Signatures.sign(SECRET, body.getBytes(StandardCharsets.UTF_8)), 400);
        assertEquals("id and type are required", r.get("error").asString());
    }
}
