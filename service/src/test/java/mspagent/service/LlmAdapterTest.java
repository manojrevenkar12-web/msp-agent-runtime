package mspagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import mspagent.core.domain.Category;
import mspagent.core.domain.Impact;
import mspagent.core.domain.WorkKind;
import mspagent.core.port.LlmCall;
import mspagent.core.port.LlmUnavailable;
import mspagent.core.port.NotesReading;
import mspagent.core.port.TriageReading;
import mspagent.service.connectors.OpenAiCompatibleLlm;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The model adapter speaks the OpenAI-compatible API with strict JSON schemas and maps throttling to a retry. */
class LlmAdapterTest {

    static String completion(String content) {
        return "{\"choices\":[{\"message\":{\"content\":" + mspagent.core.engine.Json.write(content)
                + "}}],\"usage\":{\"prompt_tokens\":812,\"completion_tokens\":64}}";
    }

    @Test
    void aTicketIsReadWithAStrictSchemaAndTokensAreCounted() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://llm.example.eu/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("https://llm.example.eu/v1/chat/completions"))
                .andExpect(jsonPath("$.temperature").value(0))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.impact.enum[0]").value("COMPANY"))
                .andRespond(withSuccess(completion("{\"impact\":\"COMPANY\",\"urgency\":\"WORK_STOPPED\","
                        + "\"category\":\"INCIDENT\",\"evidence\":[\"die ganze Firma\"]}"), MediaType.APPLICATION_JSON));
        LlmCall<TriageReading> r = new OpenAiCompatibleLlm(b.build(), "mistral-medium").readTicket("Server", "x");
        assertEquals(Impact.COMPANY, r.value().impact());
        assertEquals(Category.INCIDENT, r.value().category());
        assertEquals(812, r.inputTokens());
        assertEquals("mistral-medium", r.model());
        server.verify();
    }

    @Test
    void aNoteIsReadIntoIntervalsWithNullableStartTimes() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://llm.example.eu/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("https://llm.example.eu/v1/chat/completions"))
                .andRespond(withSuccess(completion("{\"intervals\":[{\"startMinuteOfDay\":null,\"minutes\":45,"
                        + "\"kind\":\"REMOTE\",\"quote\":\"0,75h\"},{\"startMinuteOfDay\":850,\"minutes\":45,"
                        + "\"kind\":\"ONSITE\",\"quote\":\"14:10-14:55\"}]}"), MediaType.APPLICATION_JSON));
        NotesReading n = new OpenAiCompatibleLlm(b.build(), "m").readNote("...").value();
        assertEquals(2, n.intervals().size());
        assertEquals(null, n.intervals().get(0).startMinuteOfDay());
        assertEquals(WorkKind.ONSITE, n.intervals().get(1).kind());
    }

    @Test
    void throttlingAndServerErrorsAreRetriedByTheEngine() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://llm.example.eu/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("https://llm.example.eu/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo("https://llm.example.eu/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        OpenAiCompatibleLlm llm = new OpenAiCompatibleLlm(b.build(), "m");
        assertThrows(LlmUnavailable.class, () -> llm.readNote("x"));
        assertThrows(LlmUnavailable.class, () -> llm.readNote("x"));
    }
}
