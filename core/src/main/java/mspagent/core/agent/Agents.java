package mspagent.core.agent;

import java.util.List;

/** The agents the runtime ships with. */
public final class Agents {
    private Agents() {}

    public static List<Agent> builtIn() {
        return List.of(new TriageAgent(), new TimeCaptureAgent(), new BillingAgent(), new ConciergeAgent());
    }
}
