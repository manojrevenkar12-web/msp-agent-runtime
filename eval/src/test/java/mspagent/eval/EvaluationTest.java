package mspagent.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import mspagent.core.engine.EngineSettings;
import mspagent.world.World;
import mspagent.world.WorldSettings;
import org.junit.jupiter.api.Test;

class EvaluationTest {
    static final WorldSettings WS = WorldSettings.load(Path.of("config/world.json"));
    static final EngineSettings ES = RuntimeSettings.load(Path.of("config/runtime.json"));

    @Test
    void theRuntimeNeverReadsTheTruthOrTheWorldsSettings() throws IOException {
        for (String dir : List.of("core/src/main", "service/src/main")) {
            Path d = Path.of(dir);
            if (!Files.exists(d)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(d)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    String text = Files.readString(p);
                    assertTrue(!text.contains("mspagent.world") && !text.contains("mspagent.eval")
                            && !text.contains("world.json"), p + " reaches into the simulated world");
                }
            }
        }
    }

    @Test
    void everyDesignMeetsTheSameMonthAndTheSameLuck() {
        World a = World.generate(WS, 11, false);
        World b = World.generate(WS, 11, false);
        assertEquals(a.tickets.size(), b.tickets.size());
        assertEquals(a.sessions, b.sessions);
        Map<String, Object> m1 = Evaluate.run(a, "manual", ES).score();
        Map<String, Object> m2 = Evaluate.run(b, "manual", ES).score();
        assertEquals(m1, m2);
        Map<String, Object> p1 = Evaluate.run(a, "platform", ES).score();
        Map<String, Object> p2 = Evaluate.run(b, "platform", ES).score();
        assertEquals(p1, p2, "a design is deterministic for a seed");
        assertEquals(m1.get("trueRevenueCents"), p1.get("trueRevenueCents"));
    }

    @Test
    void underFaultsTheRuntimeWritesNothingTwice() {
        World w = World.generate(WS, 23, false);
        Evaluate.Result r = Evaluate.run(w, "platform", ES);
        assertTrue((Integer) r.runtime().get("crashes") > 0, "the month had crashes to survive");
        assertTrue((Integer) r.runtime().get("duplicateDeliveries") > 0);
        assertEquals(0, r.score().get("duplicateInvoices"));
        assertEquals(0, r.score().get("duplicateUpdates"));
        assertEquals(0, r.score().get("duplicateTriageWrites"));
        assertEquals(0, r.score().get("duplicateEntryWrites"));
        assertEquals(0L, r.runtime().get("runsDead"));
    }

    @Test
    void theRuntimeIsTheOneTheHeldOutResultWasPublishedFor() throws IOException {
        Path lock = Path.of("config/heldout.lock");
        if (Files.exists(Path.of("reports/heldout.json"))) {
            assertEquals(Files.readString(lock).trim(), HeldOut.fingerprint(Path.of(".")),
                    "the runtime changed after the held-out run; new held-out seeds are needed before publishing results");
        }
    }

    @Test
    void theHeldOutSeedsAreNotDevelopmentSeeds() {
        assertTrue(WS.heldOutSeeds().stream().noneMatch(WS.devSeeds()::contains));
    }
}
