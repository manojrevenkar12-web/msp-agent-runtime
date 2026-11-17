package mspagent.service.api;

import java.util.List;
import java.util.Map;
import mspagent.core.engine.Engine;
import mspagent.core.engine.RuntimeStore;
import mspagent.core.view.Views;
import mspagent.service.security.Caller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What waits for a person, and their decision. The decision names the person in the run's record. */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalsController {
    private final RuntimeStore store;
    private final Engine engine;

    public ApprovalsController(RuntimeStore store, Engine engine) {
        this.store = store;
        this.engine = engine;
    }

    @GetMapping
    public List<Map<String, Object>> pending() {
        return store.pendingApprovals(Caller.current().tenant()).stream().map(Views::approval).toList();
    }

    public record Decision(boolean approve) {}

    @PostMapping("/{id}")
    public Map<String, Object> decide(@PathVariable String id, @RequestBody Decision d) {
        Caller c = Caller.current();
        return Views.approval(engine.decide(c.tenant(), id, d.approve(), c.user()));
    }
}
