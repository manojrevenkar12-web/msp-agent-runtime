package mspagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import mspagent.service.trace.OtlpExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Spans go to an OTLP endpoint (Langfuse's, for one) once, with the configured authorization. */
class TraceExportTest extends ServiceTest {
    static final List<String[]> RECEIVED = new CopyOnWriteArrayList<>();
    static HttpServer server;

    @DynamicPropertySource
    static void otlp(DynamicPropertyRegistry r) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/otel/v1/traces", ex -> {
            RECEIVED.add(new String[] {ex.getRequestHeaders().getFirst("Authorization"),
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)});
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        r.add("msp.otlp.endpoint", () -> "http://127.0.0.1:" + server.getAddress().getPort()
                + "/api/public/otel/v1/traces");
        r.add("msp.otlp.authorization", () -> "Basic cGstbGY6c2stbGY=");
        r.add("msp.otlp.every", () -> "1h");
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Autowired
    OtlpExporter exporter;

    @Test
    void spansAreExportedOnce() throws Exception {
        post("/api/sandbox/tickets", OPERATOR, Map.of("customerId", "C1001", "subject", "Outlook",
                "body", "Outlook synchronisiert nicht, nur mein Rechner ist betroffen."), 200);
        settle();
        exporter.export();
        int batches = RECEIVED.size();
        assertTrue(batches >= 1);
        assertEquals("Basic cGstbGY6c2stbGY=", RECEIVED.get(0)[0]);
        assertTrue(RECEIVED.get(0)[1].contains("\"gen_ai.usage.input_tokens\""));
        exporter.export();
        assertEquals(batches, RECEIVED.size(), "nothing is sent twice");
    }
}
