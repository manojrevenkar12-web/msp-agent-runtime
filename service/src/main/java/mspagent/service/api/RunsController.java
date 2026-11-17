package mspagent.service.api;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import mspagent.core.engine.Run;
import mspagent.core.engine.RunStatus;
import mspagent.core.engine.RuntimeStore;
import mspagent.core.trace.OtlpJson;
import mspagent.core.view.Views;
import mspagent.service.security.Caller;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Runs of the caller's tenant, with their kept steps and their spans. */
@RestController
@RequestMapping("/api/runs")
public class RunsController {
    private final RuntimeStore store;
    private final Clock clock;

    public RunsController(RuntimeStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String agent,
            @RequestParam(required = false) RunStatus status, @RequestParam(defaultValue = "100") int limit) {
        return store.runs(Caller.current().tenant(), new RuntimeStore.RunQuery(agent, status, Math.min(limit, 500)))
                .stream().map(Views::runSummary).toList();
    }

    Run run(String id) {
        return store.run(Caller.current().tenant(), id).orElseThrow(() -> new NoSuchElementException("no run " + id));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Run r = run(id);
        return Views.runDetail(r, store.steps(r.id()), store.spans(r.tenant(), r.id()));
    }

    @GetMapping(value = "/{id}/otlp", produces = MediaType.APPLICATION_JSON_VALUE)
    public String otlp(@PathVariable String id) {
        Run r = run(id);
        return OtlpJson.traces("msp-agent-runtime", store.spans(r.tenant(), r.id()));
    }

    /** Gives a dead or failed run another set of attempts, from its kept steps. */
    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable String id) {
        Run r = run(id);
        if (r.status() != RunStatus.DEAD && r.status() != RunStatus.FAILED) {
            throw new IllegalStateException("only dead or failed runs can be retried; this one is " + r.status());
        }
        Run again = new Run(r.id(), r.tenant(), r.agentId(), r.agentVersion(), r.event(), RunStatus.PENDING, 0, null,
                null, null, r.createdAt(), clock.instant(), null, false);
        store.saveRun(again);
        return Views.runSummary(again);
    }
}
