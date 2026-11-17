package mspagent.core.engine;

/** Another run is in the middle of the same write; this run tries again later. */
public final class EffectInFlight extends RuntimeException {
    public EffectInFlight(String key) {
        super("write " + key + " is in progress in another run");
    }
}
