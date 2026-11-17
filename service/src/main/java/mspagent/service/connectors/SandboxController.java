package mspagent.service.connectors;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import mspagent.core.event.Event;
import mspagent.core.event.EventTypes;
import mspagent.core.port.Ticket;
import mspagent.service.ingest.Ingest;
import mspagent.service.security.Caller;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plays the tenant's own systems in the sandbox: opening a ticket, writing a note, ending a remote session or a visit,
 * changing a status and closing a month write into {@link SandboxSystems} and send the event those systems would.
 */
@RestController
@RequestMapping("/api/sandbox")
@ConditionalOnProperty(name = "msp.connectors.mode", havingValue = "sandbox", matchIfMissing = true)
public class SandboxController {
    private final SandboxSystems sandbox;
    private final Ingest ingest;
    private final Clock clock;
    private final AtomicInteger ids = new AtomicInteger((int) (System.currentTimeMillis() / 1000 % 100000) * 10);

    public SandboxController(SandboxSystems sandbox, Ingest ingest, Clock clock) {
        this.sandbox = sandbox;
        this.ingest = ingest;
        this.clock = clock;
    }

    private Map<String, Object> send(String source, String type, Map<String, String> data) {
        String tenant = Caller.current().tenant();
        Event e = new Event(tenant, source, UUID.randomUUID().toString(), type, clock.instant(), data);
        ingest.accept(e);
        Map<String, Object> out = new LinkedHashMap<>(data);
        out.put("eventId", e.id());
        out.put("eventType", type);
        return out;
    }

    public record NewTicket(String customerId, String subject, String body, Integer openedMinutesAgo) {}

    @PostMapping("/tickets")
    public Map<String, Object> ticket(@RequestBody NewTicket b) {
        Ticket t = sandbox.open(Caller.current().tenant(), "T" + ids.incrementAndGet(), b.customerId(), b.subject(),
                b.body(), b.openedMinutesAgo() == null ? 0 : b.openedMinutesAgo());
        return send("tickets", EventTypes.TICKET_CREATED, Map.of("ticketId", t.id()));
    }

    public record NewNote(String technicianId, String text, boolean internal) {}

    @PostMapping("/tickets/{id}/notes")
    public Map<String, Object> note(@PathVariable String id, @RequestBody NewNote b) {
        String noteId = id + "-N" + ids.incrementAndGet();
        sandbox.note(Caller.current().tenant(), id, noteId, b.technicianId(), b.text(), b.internal());
        return send("tickets", EventTypes.NOTE_ADDED, Map.of("ticketId", id, "noteId", noteId));
    }

    public record Work(String technicianId, int minutes) {}

    @PostMapping("/tickets/{id}/remote-sessions")
    public Map<String, Object> session(@PathVariable String id, @RequestBody Work b) {
        String sid = id + "-R" + ids.incrementAndGet();
        sandbox.session(Caller.current().tenant(), id, sid, b.technicianId(), b.minutes());
        return send("rmm", EventTypes.SESSION_ENDED, Map.of("ticketId", id, "sessionId", sid));
    }

    @PostMapping("/tickets/{id}/visits")
    public Map<String, Object> visit(@PathVariable String id, @RequestBody Work b) {
        String vid = id + "-V" + ids.incrementAndGet();
        sandbox.visit(Caller.current().tenant(), id, vid, b.technicianId(), b.minutes());
        return send("field", EventTypes.CHECKED_OUT, Map.of("ticketId", id, "checkInId", vid));
    }

    public record NewStatus(String status) {}

    @PostMapping("/tickets/{id}/status")
    public Map<String, Object> status(@PathVariable String id, @RequestBody NewStatus b) {
        sandbox.status(Caller.current().tenant(), id, b.status());
        return send("tickets", EventTypes.TICKET_STATUS, Map.of("ticketId", id, "status", b.status()));
    }

    public record MonthClose(String month) {}

    @PostMapping("/month-close")
    public List<Map<String, Object>> close(@RequestBody MonthClose b) {
        String tenant = Caller.current().tenant();
        return sandbox.customers(tenant).stream()
                .map(cid -> send("erp", EventTypes.MONTH_CLOSED, Map.of("customerId", cid, "month", b.month())))
                .toList();
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        String tenant = Caller.current().tenant();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("invoices", sandbox.invoices(tenant));
        m.put("updates", sandbox.updates(tenant).stream().map(u -> Map.of("ticketId", u[0], "text", u[1])).toList());
        return m;
    }
}
