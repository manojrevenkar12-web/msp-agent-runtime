package mspagent.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import mspagent.core.engine.EngineSettings;

/** Reads {@code config/runtime.json}. */
final class RuntimeSettings {
    private RuntimeSettings() {}

    static EngineSettings load(Path p) {
        try {
            return EngineSettings.fromJson(Files.readString(p));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
