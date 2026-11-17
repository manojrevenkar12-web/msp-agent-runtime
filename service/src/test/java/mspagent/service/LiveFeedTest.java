package mspagent.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The console's live feed gets the tenant's run changes, and refuses a connection without a key. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "msp.relay-millis=3600000", "msp.tick-millis=3600000", "msp.bus=inline", "msp.connectors.mode=sandbox",
    "msp.llm.mode=sandbox", "spring.flyway.clean-disabled=false"})
class LiveFeedTest extends ServiceTest {
    @LocalServerPort
    int port;

    @Test
    void runChangesArriveOverTheWebSocket() throws Exception {
        LinkedBlockingQueue<String> got = new LinkedBlockingQueue<>();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/runs?key=" + VIEWER), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                        got.add(data.toString());
                        w.request(1);
                        return CompletableFuture.completedFuture(null);
                    }
                }).get(10, TimeUnit.SECONDS);
        post("/api/sandbox/tickets", OPERATOR, Map.of("customerId", "C1001", "subject", "WLAN",
                "body", "Das WLAN bricht ab, unser Team ist betroffen."), 200);
        settle();
        String m = got.poll(10, TimeUnit.SECONDS);
        assertTrue(m != null && m.contains("\"type\":\"run\"") && m.contains("\"agentId\":\"triage\""), m);
        ws.abort();

        boolean refused;
        try {
            HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/runs?key=nope"), new WebSocket.Listener() {})
                    .get(10, TimeUnit.SECONDS);
            refused = false;
        } catch (java.util.concurrent.ExecutionException e) {
            refused = true;
        }
        assertTrue(refused);
    }
}
