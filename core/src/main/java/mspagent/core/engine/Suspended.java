package mspagent.core.engine;

/** Thrown inside a run that waits for a person. The engine catches it. */
public final class Suspended extends RuntimeException {
    public Suspended(String approvalId) {
        super(approvalId, null, false, false);
    }
}
