package mspagent.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import mspagent.core.engine.EngineSettings;
import mspagent.core.engine.Hash;
import mspagent.core.engine.Json;
import mspagent.world.WorldSettings;

/**
 * The held-out run: new seeds and kinds of input the development set never contained. Every file that decides what
 * the runtime does is pinned in {@code config/heldout.lock} first; the run refuses a changed runtime and refuses to run
 * twice. Its result is published as it came out.
 */
final class HeldOut {
    private HeldOut() {}

    static String fingerprint(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root.resolve("core/src/main"))) {
            s.filter(Files::isRegularFile).forEach(files::add);
        }
        files.add(root.resolve("config/runtime.json"));
        files.sort(Path::compareTo);
        StringBuilder all = new StringBuilder();
        for (Path p : files) {
            all.append(root.relativize(p)).append('\n').append(Hash.sha256(Files.readString(p))).append('\n');
        }
        return Hash.sha256(all.toString());
    }

    static void writeLock(Path root) throws IOException {
        Files.writeString(root.resolve("config/heldout.lock"), fingerprint(root) + "\n");
        System.out.println("pinned the runtime in config/heldout.lock");
    }

    static void run(Path root, WorldSettings ws, EngineSettings es) throws IOException {
        Path out = root.resolve("reports/heldout.json");
        if (Files.exists(out)) {
            throw new IllegalStateException("the held-out set was already run; its result stands as published");
        }
        Path lock = root.resolve("config/heldout.lock");
        if (!Files.exists(lock) || !Files.readString(lock).trim().equals(fingerprint(root))) {
            throw new IllegalStateException("the runtime differs from config/heldout.lock");
        }
        Map<String, Object> rep = Evaluate.report(ws, ws.heldOutSeeds(), true, es);
        Files.writeString(out, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rep) + "\n");
    }
}
