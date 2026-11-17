package mspagent.core.engine;

/** Thrown inside a run when a person rejected what it asked for. The run fails without retrying. */
public final class Rejected extends RuntimeException {
    public Rejected(String message) {
        super(message, null, false, false);
    }
}
