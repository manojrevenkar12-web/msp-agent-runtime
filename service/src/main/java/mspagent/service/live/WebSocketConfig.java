package mspagent.service.live;

import java.util.Map;
import mspagent.service.security.ApiKeys;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/** The live run feed. Browsers cannot set headers on a WebSocket, so the key comes as a query parameter. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RunSocket socket;
    private final ApiKeys keys;

    public WebSocketConfig(RunSocket socket, ApiKeys keys) {
        this.socket = socket;
        this.keys = keys;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry r) {
        r.addHandler(socket, "/ws/runs").setAllowedOriginPatterns("*").addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h,
                    Map<String, Object> attrs) {
                String key = UriComponentsBuilder.fromUri(req.getURI()).build().getQueryParams().getFirst("key");
                return keys.find(key).map(c -> {
                    attrs.put("caller", c);
                    return true;
                }).orElseGet(() -> {
                    res.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                    return false;
                });
            }

            @Override
            public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Exception e) {}
        });
    }
}
