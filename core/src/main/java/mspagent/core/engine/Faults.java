package mspagent.core.engine;

/** Points where the evaluation may kill a worker. Production uses {@link #NONE}. */
public interface Faults {
    Faults NONE = (point, runId, detail) -> {};

    /** Called after a step was kept and after an outside write returned but before it was recorded. */
    void at(String point, String runId, String detail);
}
