package mspagent.core.engine;

/**
 * A worker dying at a chosen point, injected by the evaluation and the tests. It is an Error so that no catch block in
 * the engine or an agent can swallow it, which is what a killed process looks like.
 */
public final class SimulatedCrash extends Error {
    public SimulatedCrash(String where) {
        super(where, null, false, false);
    }
}
