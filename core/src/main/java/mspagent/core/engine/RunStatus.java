package mspagent.core.engine;

/** Where a run is. DONE, FAILED and DEAD are final. */
public enum RunStatus {
    PENDING,
    RUNNING,
    RETRY,
    WAITING_APPROVAL,
    DONE,
    FAILED,
    DEAD;

    public boolean isFinal() {
        return this == DONE || this == FAILED || this == DEAD;
    }
}
