package mspagent.core.engine;

/** An error that another attempt will not fix (bad input, missing contract). The run fails without retrying. */
public class PermanentFailure extends RuntimeException {
    public PermanentFailure(String message) {
        super(message);
    }
}
