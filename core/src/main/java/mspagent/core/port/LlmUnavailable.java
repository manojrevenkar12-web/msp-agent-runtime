package mspagent.core.port;

/** The model provider timed out or throttled. The runtime retries the run later. */
public class LlmUnavailable extends RuntimeException {
    public LlmUnavailable(String message) {
        super(message);
    }
}
