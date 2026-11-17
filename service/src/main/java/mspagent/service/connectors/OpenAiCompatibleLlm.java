package mspagent.service.connectors;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import mspagent.core.domain.Category;
import mspagent.core.domain.Impact;
import mspagent.core.domain.Urgency;
import mspagent.core.domain.WorkKind;
import mspagent.core.engine.Json;
import mspagent.core.port.Llm;
import mspagent.core.port.LlmCall;
import mspagent.core.port.LlmUnavailable;
import mspagent.core.port.Note;
import mspagent.core.port.NotesReading;
import mspagent.core.port.TriageReading;
import mspagent.core.port.UpdateRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Any OpenAI-compatible chat completions endpoint (an EU-hosted Mistral, Azure OpenAI in an EU region, vLLM on own
 * GPUs) with JSON-schema structured output and temperature 0. Throttling, server errors and timeouts become
 * {@link LlmUnavailable}, which the engine retries with backoff.
 */
public final class OpenAiCompatibleLlm implements Llm {
    private final RestClient http;
    private final String model;
    private final String triagePrompt = prompt("triage");
    private final String notesPrompt = prompt("notes");
    private final String updatePrompt = prompt("update");

    public OpenAiCompatibleLlm(RestClient http, String model) {
        this.http = http;
        this.model = model;
    }

    private static String prompt(String name) {
        try {
            return new ClassPathResource("prompts/" + name + ".md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectNode enumOf(Enum<?>[] values) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("type", "string");
        ArrayNode a = n.putArray("enum");
        for (Enum<?> v : values) {
            a.add(v.name());
        }
        return n;
    }

    private static ObjectNode object(String... required) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("type", "object");
        n.put("additionalProperties", false);
        ArrayNode r = n.putArray("required");
        for (String s : required) {
            r.add(s);
        }
        n.putObject("properties");
        return n;
    }

    static ObjectNode triageSchema() {
        ObjectNode s = object("impact", "urgency", "category", "evidence");
        ObjectNode p = (ObjectNode) s.get("properties");
        p.set("impact", enumOf(Impact.values()));
        p.set("urgency", enumOf(Urgency.values()));
        p.set("category", enumOf(Category.values()));
        ObjectNode ev = p.putObject("evidence");
        ev.put("type", "array");
        ev.putObject("items").put("type", "string");
        return s;
    }

    static ObjectNode notesSchema() {
        ObjectNode item = object("startMinuteOfDay", "minutes", "kind", "quote");
        ObjectNode p = (ObjectNode) item.get("properties");
        ArrayNode t = p.putObject("startMinuteOfDay").putArray("type");
        t.add("integer");
        t.add("null");
        p.putObject("minutes").put("type", "integer");
        p.set("kind", enumOf(WorkKind.values()));
        p.putObject("quote").put("type", "string");
        ObjectNode s = object("intervals");
        ObjectNode arr = ((ObjectNode) s.get("properties")).putObject("intervals");
        arr.put("type", "array");
        arr.set("items", item);
        return s;
    }

    private JsonNode call(String system, String user, String schemaName, ObjectNode schema) {
        ObjectNode req = Json.MAPPER.createObjectNode();
        req.put("model", model);
        req.put("temperature", 0);
        ArrayNode msgs = req.putArray("messages");
        msgs.addObject().put("role", "system").put("content", system);
        msgs.addObject().put("role", "user").put("content", user);
        if (schema != null) {
            ObjectNode rf = req.putObject("response_format");
            rf.put("type", "json_schema");
            ObjectNode js = rf.putObject("json_schema");
            js.put("name", schemaName);
            js.put("strict", true);
            js.set("schema", schema);
        }
        String body;
        try {
            body = http.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                    .body(Json.write(req)).retrieve().body(String.class);
        } catch (HttpClientErrorException.TooManyRequests | HttpServerErrorException | ResourceAccessException e) {
            throw new LlmUnavailable(e.getMessage());
        }
        return Json.MAPPER.readTree(body);
    }

    private static int tokens(JsonNode r, String field) {
        JsonNode u = r.path("usage").path(field);
        return u.isMissingNode() ? 0 : u.asInt();
    }

    private static String content(JsonNode r) {
        return r.path("choices").path(0).path("message").path("content").asString();
    }

    @Override
    public LlmCall<TriageReading> readTicket(String subject, String body) {
        JsonNode r = call(triagePrompt, "Subject: " + subject + "\n\n" + body, "triage", triageSchema());
        TriageReading t = Json.read(content(r), TriageReading.class);
        return new LlmCall<>(t, model, tokens(r, "prompt_tokens"), tokens(r, "completion_tokens"));
    }

    @Override
    public LlmCall<NotesReading> readNote(String text) {
        JsonNode r = call(notesPrompt, text, "notes", notesSchema());
        NotesReading n = Json.read(content(r), NotesReading.class);
        return new LlmCall<>(n, model, tokens(r, "prompt_tokens"), tokens(r, "completion_tokens"));
    }

    @Override
    public LlmCall<String> draftUpdate(UpdateRequest q) {
        StringBuilder u = new StringBuilder("Ticket: " + q.subject() + "\nStatus: " + q.status() + "\nTechnician: "
                + q.technicianName() + "\n");
        List<Note> pub = q.notes().stream().filter(n -> !n.internal()).toList();
        for (Note n : pub) {
            u.append("Public note: ").append(n.text()).append('\n');
        }
        JsonNode r = call(updatePrompt, u.toString(), null, null);
        return new LlmCall<>(content(r), model, tokens(r, "prompt_tokens"), tokens(r, "completion_tokens"));
    }
}
