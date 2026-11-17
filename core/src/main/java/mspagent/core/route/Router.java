package mspagent.core.route;

import java.util.List;
import mspagent.core.agent.Agent;
import mspagent.core.event.Event;

/**
 * Decides which agents an event goes to. An agent receives an event only if it declares the event type and the
 * tenant has published a version of it.
 */
public final class Router {
    private final List<Route> routes;

    public Router(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }

    public static Router fromAgents(List<Agent> agents) {
        return new Router(agents.stream()
                .flatMap(a -> a.eventTypes().stream().map(t -> new Route(t, a.id())))
                .toList());
    }

    public List<String> agentsFor(Event e) {
        return routes.stream().filter(r -> r.eventType().equals(e.type())).map(Route::agentId).distinct().toList();
    }

    public List<Route> routes() {
        return routes;
    }
}
