package mspagent.service.live;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mspagent.core.engine.Json;
import mspagent.core.engine.Run;
import mspagent.core.view.Views;
import mspagent.service.security.Caller;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Pushes every change of a tenant's runs to that tenant's open consoles ({@code /ws/runs?key=...}). */
@Component
public class RunSocket extends TextWebSocketHandler {
    private final Map<String, Set<WebSocketSession>> byTenant = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession s) {
        Caller c = (Caller) s.getAttributes().get("caller");
        byTenant.computeIfAbsent(c.tenant(), t -> ConcurrentHashMap.newKeySet())
                .add(new ConcurrentWebSocketSessionDecorator(s, 2000, 256 * 1024));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        byTenant.values().forEach(set -> set.removeIf(x -> x.getId().equals(s.getId())));
    }

    public void publish(Run run) {
        if (run.dryRun()) {
            return;
        }
        Set<WebSocketSession> sessions = byTenant.get(run.tenant());
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        TextMessage m = new TextMessage(Json.write(Map.of("type", "run", "run", Views.runSummary(run))));
        for (WebSocketSession s : sessions) {
            try {
                s.sendMessage(m);
            } catch (IOException | IllegalStateException e) {
                sessions.remove(s);
            }
        }
    }

    public int open(String tenant) {
        return byTenant.getOrDefault(tenant, Set.of()).size();
    }
}
