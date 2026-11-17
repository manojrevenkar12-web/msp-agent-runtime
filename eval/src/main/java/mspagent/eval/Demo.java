package mspagent.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mspagent.core.agent.Agent;
import mspagent.core.agent.Agents;
import mspagent.core.engine.Approval;
import mspagent.core.engine.EngineSettings;
import mspagent.core.engine.Json;
import mspagent.core.engine.Run;
import mspagent.core.engine.RunStatus;
import mspagent.core.engine.RuntimeStore;
import mspagent.core.view.Views;
import mspagent.world.World;
import mspagent.world.WorldSettings;
import tools.jackson.databind.JsonNode;

/**
 * Writes {@code console/public/demo.json}: the console's data when no runtime is connected, taken from the platform
 * on the first development month with the office's approvals still open. The shapes are the service API's.
 */
final class Demo {
    private Demo() {}

    static void write(Path root, WorldSettings ws, EngineSettings es) throws java.io.IOException {
        World w = World.generate(ws, ws.devSeeds().get(0), false);
        Evaluate.Result r = Evaluate.run(w, "platform", es, false);
        RuntimeStore store = r.platform().store;
        List<Run> all = store.runs(w.tenant, new RuntimeStore.RunQuery(null, null, Integer.MAX_VALUE));
        Set<Run> pick = new LinkedHashSet<>();
        all.stream().filter(x -> x.status() == RunStatus.WAITING_APPROVAL).limit(8).forEach(pick::add);
        all.stream().filter(x -> x.attempts() > 1).sorted(Comparator.comparing(Run::createdAt).reversed()).limit(14)
                .forEach(pick::add);
        for (Agent a : Agents.builtIn()) {
            all.stream().filter(x -> x.agentId().equals(a.id())).limit(28).forEach(pick::add);
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        pick.stream().sorted(Comparator.comparing(Run::updatedAt).reversed())
                .forEach(x -> runs.add(Views.runDetail(x, store.steps(x.id()), store.spans(w.tenant, x.id()))));

        Map<String, Object> demo = new LinkedHashMap<>();
        demo.put("note", "Simulated: seed " + w.seed + ", " + w.month + ", " + w.tickets.size()
                + " tickets at a service provider with " + w.contracts.size() + " customers. Not a live system.");
        demo.put("tenant", w.tenant);
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Agent a : Agents.builtIn()) {
            agents.add(Views.agent(a, store.versions(w.tenant, a.id())));
        }
        demo.put("agents", agents);
        demo.put("runs", runs);
        List<Map<String, Object>> approvals = new ArrayList<>();
        for (Approval a : store.pendingApprovals(w.tenant)) {
            approvals.add(Views.approval(a));
        }
        demo.put("approvals", approvals);
        demo.put("stats", r.runtime());
        JsonNode dev = Json.MAPPER.readTree(Files.readString(root.resolve("reports/dev.json")));
        demo.put("evaluation", dev.get("designs"));
        demo.put("evaluationSeeds", dev.get("seeds"));
        Files.createDirectories(root.resolve("console/public"));
        Files.writeString(root.resolve("console/public/demo.json"), Json.write(demo) + "\n");
        System.out.println("wrote console/public/demo.json with " + runs.size() + " runs");
    }
}
