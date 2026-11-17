package mspagent.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mspagent.core.engine.EngineSettings;
import mspagent.core.engine.Json;
import mspagent.core.engine.Run;
import mspagent.core.engine.RunStatus;
import mspagent.core.engine.RuntimeStore;
import mspagent.core.trace.Span;
import mspagent.world.Scoring;
import mspagent.world.SimLlm;
import mspagent.world.World;
import mspagent.world.WorldSettings;
import mspagent.world.WorldSystems;

/** Runs every design on the same simulated months and writes the report the README and console are built from. */
public final class Evaluate {
    static final List<String> DESIGNS = List.of("manual", "modelDecides", "platform");

    private Evaluate() {}

    /** One design on one world. */
    record Result(Map<String, Object> score, Map<String, Object> runtime, PlatformDesign platform, World world,
            WorldSystems systems) {}

    static Result run(World w, String design, EngineSettings settings) {
        return run(w, design, settings, true);
    }

    static Result run(World w, String design, EngineSettings settings, boolean officeApproves) {
        SimClock clock = new SimClock(w.monthStart.minusSeconds(3600));
        WorldSystems sys = new WorldSystems(w, clock);
        SimLlm llm = new SimLlm(w);
        Timeline t = new Timeline(w, sys, clock);
        Map<String, Object> runtime = new LinkedHashMap<>();
        PlatformDesign pd = null;
        switch (design) {
            case "manual" -> t.play(new ManualDesign(w));
            case "modelDecides" -> {
                NaiveDesign nd = new NaiveDesign(w, llm);
                t.play(nd);
                runtime.put("crashes", nd.crashes);
            }
            case "platform" -> {
                pd = new PlatformDesign(w, settings);
                pd.officeApproves = officeApproves;
                pd.bind(t, llm);
                t.play(pd);
                runtime.putAll(runtimeStats(pd, w));
            }
            default -> throw new IllegalArgumentException(design);
        }
        runtime.put("deliveries", t.deliveries);
        runtime.put("duplicateDeliveries", t.duplicateDeliveries);
        runtime.put("lateDeliveries", t.lateDeliveries);
        runtime.put("llmCalls", llm.callCount);
        runtime.put("llmUnavailable", llm.unavailableCount);
        return new Result(Scoring.score(w, sys, Map.of()), runtime, pd, w, sys);
    }

    static Map<String, Object> runtimeStats(PlatformDesign pd, World w) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Run> runs = pd.store.runs(w.tenant, new RuntimeStore.RunQuery(null, null, Integer.MAX_VALUE));
        Map<String, Map<String, Integer>> byAgent = new TreeMap<>();
        int retried = 0;
        long cost = 0;
        long in = 0;
        long out = 0;
        List<Long> latency = new ArrayList<>();
        for (Run r : runs) {
            byAgent.computeIfAbsent(r.agentId(), x -> new TreeMap<>()).merge(r.status().name(), 1, Integer::sum);
            if (r.attempts() > 1) {
                retried++;
            }
            if (r.status() == RunStatus.DONE) {
                latency.add(r.updatedAt().getEpochSecond() - r.event().occurredAt().getEpochSecond());
            }
            for (Span s : pd.store.spans(w.tenant, r.id())) {
                if (s.name().startsWith("chat ") && !s.error()) {
                    cost += Long.parseLong(s.attributes().get("msp.cost_micros"));
                    in += Long.parseLong(s.attributes().get("gen_ai.usage.input_tokens"));
                    out += Long.parseLong(s.attributes().get("gen_ai.usage.output_tokens"));
                }
            }
        }
        latency.sort(Long::compare);
        m.put("runs", runs.size());
        m.put("runsByAgent", byAgent);
        m.put("runsRetried", retried);
        m.put("runsDead", runs.stream().filter(r -> r.status() == RunStatus.DEAD).count());
        m.put("crashes", pd.crashes);
        m.put("approvals", pd.approvals);
        m.put("inputTokens", in);
        m.put("outputTokens", out);
        m.put("costMicros", cost);
        m.put("latencyP50Seconds", pct(latency, 0.5));
        m.put("latencyP95Seconds", pct(latency, 0.95));
        return m;
    }

    private static long pct(List<Long> xs, double p) {
        return xs.isEmpty() ? 0 : xs.get(Math.min(xs.size() - 1, (int) Math.floor(p * xs.size())));
    }

    static Map<String, Object> report(WorldSettings ws, List<Long> seeds, boolean heldOut, EngineSettings es) {
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("generated", "./gradlew :eval:evaluate");
        rep.put("set", heldOut ? "held-out" : "dev");
        rep.put("month", ws.month());
        rep.put("seeds", seeds);
        if (heldOut) {
            rep.put("heldOutOnly", ws.heldOutOnly());
        }
        List<Map<String, Object>> perSeed = new ArrayList<>();
        Map<String, List<Map<String, Object>>> byDesign = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> rtByDesign = new LinkedHashMap<>();
        List<Map<String, Object>> worlds = new ArrayList<>();
        for (long seed : seeds) {
            World w = World.generate(ws, seed, heldOut);
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put("seed", seed);
            wm.put("tickets", w.tickets.size());
            wm.put("sessions", w.sessions.size());
            wm.put("notes", w.notes.size());
            wm.put("customers", w.contracts.size());
            wm.put("technicians", w.technicians.size());
            worlds.add(wm);
            Map<String, Object> ps = new LinkedHashMap<>();
            ps.put("seed", seed);
            for (String d : DESIGNS) {
                long t0 = System.currentTimeMillis();
                Result r = run(w, d, es);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("score", r.score());
                entry.put("runtime", r.runtime());
                ps.put(d, entry);
                byDesign.computeIfAbsent(d, x -> new ArrayList<>()).add(r.score());
                rtByDesign.computeIfAbsent(d, x -> new ArrayList<>()).add(r.runtime());
                System.out.printf("seed %d %-13s %5.1fs unbilled %,10d c  over %,9d c  urgent breaches %d%n", seed, d,
                        (System.currentTimeMillis() - t0) / 1000.0, (Long) r.score().get("unbilledCents"),
                        (Long) r.score().get("overbilledCents"), (Integer) r.score().get("urgentBreaches"));
            }
            perSeed.add(ps);
        }
        rep.put("worlds", worlds);
        Map<String, Object> designs = new LinkedHashMap<>();
        for (String d : DESIGNS) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("score", mean(byDesign.get(d)));
            dm.put("runtime", mean(rtByDesign.get(d)));
            designs.put(d, dm);
        }
        rep.put("designs", designs);
        rep.put("perSeed", perSeed);
        return rep;
    }

    /** Means of the numeric values over seeds; nested maps averaged per key. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> mean(List<Map<String, Object>> xs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : xs.get(0).keySet()) {
            Object first = xs.get(0).get(k);
            if (first instanceof Number) {
                double s = 0;
                for (Map<String, Object> x : xs) {
                    s += ((Number) x.get(k)).doubleValue();
                }
                out.put(k, Math.round(s / xs.size() * 10) / 10.0);
            } else if (first instanceof Map) {
                List<Map<String, Object>> sub = new ArrayList<>();
                for (Map<String, Object> x : xs) {
                    sub.add(flatten((Map<String, Object>) x.get(k)));
                }
                out.put(k, sumNested(sub));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Map<String, Object> m) {
        Map<String, Object> out = new TreeMap<>();
        m.forEach((k, v) -> {
            if (v instanceof Map) {
                ((Map<String, Object>) v).forEach((k2, v2) -> out.put(k + "." + k2, v2));
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    private static Map<String, Object> sumNested(List<Map<String, Object>> xs) {
        Map<String, Object> out = new TreeMap<>();
        for (Map<String, Object> x : xs) {
            x.forEach((k, v) -> out.merge(k, ((Number) v).doubleValue() / xs.size(), (a, b) ->
                    ((Number) a).doubleValue() + ((Number) b).doubleValue()));
        }
        out.replaceAll((k, v) -> Math.round(((Number) v).doubleValue() * 10) / 10.0);
        return out;
    }

    public static void main(String[] args) throws Exception {
        String cmd = args.length == 0 ? "dev" : args[0];
        Path root = Path.of(".");
        WorldSettings ws = WorldSettings.load(root.resolve("config/world.json"));
        EngineSettings es = RuntimeSettings.load(root.resolve("config/runtime.json"));
        switch (cmd) {
            case "dev" -> {
                Instant t0 = Instant.now();
                Map<String, Object> rep = report(ws, ws.devSeeds(), false, es);
                rep.put("seconds", Instant.now().getEpochSecond() - t0.getEpochSecond());
                Files.createDirectories(root.resolve("reports"));
                Files.writeString(root.resolve("reports/dev.json"),
                        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(withoutSeconds(rep)) + "\n");
            }
            case "heldout" -> HeldOut.run(root, ws, es);
            case "lock" -> HeldOut.writeLock(root);
            case "demo" -> Demo.write(root, ws, es);
            case "readme" -> Readme.write(root);
            default -> throw new IllegalArgumentException("unknown command " + cmd);
        }
    }

    static Map<String, Object> withoutSeconds(Map<String, Object> rep) {
        rep.remove("seconds");
        return rep;
    }
}
