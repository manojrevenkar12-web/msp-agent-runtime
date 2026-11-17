package mspagent.core.engine;

/** Told about every change of a run (the service pushes these to the console over WebSocket). */
public interface RunListener {
    RunListener NONE = run -> {};

    void changed(Run run);
}
