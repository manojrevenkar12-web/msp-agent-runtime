package mspagent.service.runtime;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import mspagent.core.agent.Agent;
import mspagent.core.agent.Agents;
import mspagent.core.engine.Engine;
import mspagent.core.engine.EngineSettings;
import mspagent.core.engine.Faults;
import mspagent.core.engine.RuntimeStore;
import mspagent.core.port.Llm;
import mspagent.core.port.Tools;
import mspagent.core.route.Router;
import mspagent.service.Settings;
import mspagent.service.connectors.HttpConnectors;
import mspagent.service.connectors.OpenAiCompatibleLlm;
import mspagent.service.connectors.SandboxLlm;
import mspagent.service.connectors.SandboxSystems;
import mspagent.service.similar.TicketHistory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Wires the engine: the PostgreSQL store, the built-in agents, the connectors and the model chosen in settings. */
@Configuration
public class RuntimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EngineSettings engineSettings() throws IOException {
        return EngineSettings.fromJson(new ClassPathResource("config/runtime.json")
                .getContentAsString(StandardCharsets.UTF_8));
    }

    @Bean
    List<Agent> agents() {
        return Agents.builtIn();
    }

    @Bean
    SandboxSystems sandboxSystems(Clock clock) {
        return new SandboxSystems(clock);
    }

    static RestClient http(String baseUrl, String bearer, Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(client);
        f.setReadTimeout(timeout);
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl).requestFactory(f);
        if (bearer != null && !bearer.isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + bearer);
        }
        return b.build();
    }

    @Bean
    Llm llm(Settings s) {
        if ("openai".equals(s.llm().mode())) {
            return new OpenAiCompatibleLlm(http(s.llm().baseUrl(), s.llm().apiKey(), s.llm().timeout()), s.llm().model());
        }
        return new SandboxLlm();
    }

    @Bean
    Tools tools(Settings s, SandboxSystems sandbox, Llm llm, TicketHistory history) {
        if ("http".equals(s.connectors().mode())) {
            HttpConnectors c = new HttpConnectors(http(s.connectors().baseUrl(), s.connectors().token(),
                    Duration.ofSeconds(20)));
            return new Tools(c, c, c, c, llm, history);
        }
        return new Tools(sandbox, sandbox, sandbox, sandbox, llm, history);
    }

    @Bean
    Engine engine(RuntimeStore store, List<Agent> agents, Tools tools, Clock clock, EngineSettings settings,
            RunEvents events) {
        return new Engine(store, Router.fromAgents(agents), agents, tools, clock, settings, Faults.NONE, events);
    }
}
