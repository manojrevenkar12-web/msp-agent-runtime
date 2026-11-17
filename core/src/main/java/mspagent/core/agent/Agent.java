package mspagent.core.agent;

import java.util.List;
import mspagent.core.engine.Ctx;

/**
 * An agent: code that handles one kind of event through the runtime's {@link Ctx}. Everything it reads goes through
 * {@link Ctx#step}, everything it writes through {@link Ctx#effect}, so a run that dies half way resumes without
 * doing anything twice.
 */
public interface Agent {

    String id();

    String title();

    String description();

    List<String> eventTypes();

    List<ParamSpec> params();

    /**
     * The fields of the run's "decision" step a test case checks by default: the outcome, not details that change
     * with the time of the replay (who has the fewest open tickets, the wording of a reason).
     */
    default List<String> testedFields() {
        return List.of();
    }

    void run(Ctx ctx);
}
