package mspagent.service.connectors;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mspagent.core.domain.Category;
import mspagent.core.domain.Contract;
import mspagent.core.domain.ContractType;
import mspagent.core.domain.EntryStatus;
import mspagent.core.domain.Invoice;
import mspagent.core.domain.SlaTier;
import mspagent.core.domain.TimeEntry;
import mspagent.core.port.CheckIn;
import mspagent.core.port.Erp;
import mspagent.core.port.FieldApp;
import mspagent.core.port.Note;
import mspagent.core.port.RemoteSession;
import mspagent.core.port.Rmm;
import mspagent.core.port.Technician;
import mspagent.core.port.Ticket;
import mspagent.core.port.TicketSystem;
import mspagent.core.port.TriageDecision;

/**
 * In-memory ticket system, RMM, field app and ERP per tenant, for the demo and the tests ({@code msp.connectors.mode:
 * sandbox}). The sandbox API ({@link SandboxController}) writes into it and sends the matching events.
 */
public final class SandboxSystems implements TicketSystem, Rmm, FieldApp, Erp {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static final class Data {
        final Map<String, Ticket> tickets = new LinkedHashMap<>();
        final Map<String, List<Note>> notes = new HashMap<>();
        final Map<String, TimeEntry> entries = new LinkedHashMap<>();
        final List<RemoteSession> sessions = new ArrayList<>();
        final List<CheckIn> checkIns = new ArrayList<>();
        final Map<String, Contract> contracts = new LinkedHashMap<>();
        final List<Technician> technicians = new ArrayList<>();
        final List<Invoice> invoices = new ArrayList<>();
        final List<String[]> updates = new ArrayList<>();
        final Set<String> refs = new HashSet<>();
    }

    private final Map<String, Data> tenants = new ConcurrentHashMap<>();
    private final Clock clock;

    public SandboxSystems(Clock clock) {
        this.clock = clock;
    }

    private Data d(String tenant) {
        return tenants.computeIfAbsent(tenant, t -> seed());
    }

    private static Data seed() {
        Data d = new Data();
        d.contracts.put("C1001", new Contract("C1001", "Kanzlei Weber, Hannover", ContractType.MANAGED_FLAT,
                SlaTier.GOLD, 120_00, 1_800_00, 0, 45_00));
        d.contracts.put("C1002", new Contract("C1002", "Autohaus Brandt, Celle", ContractType.PREPAID_HOURS,
                SlaTier.SILVER, 110_00, 0, 600, 55_00));
        d.contracts.put("C1003", new Contract("C1003", "Praxis Dr. Yilmaz, Hildesheim", ContractType.TIME_AND_MATERIAL,
                SlaTier.BRONZE, 105_00, 0, 0, 40_00));
        d.technicians.add(new Technician("tech-1", "Jonas", Set.of(Category.values()), 0));
        d.technicians.add(new Technician("tech-2", "Lea", Set.of(Category.INCIDENT, Category.SERVICE_REQUEST,
                Category.SECURITY_ALERT), 0));
        d.technicians.add(new Technician("tech-3", "Mehmet", Set.of(Category.INSTALLATION, Category.PROJECT,
                Category.INCIDENT), 0));
        return d;
    }

    // --- what the sandbox API does ---

    /** Opens a ticket; {@code openedMinutesAgo} lets a demo start with work that already happened. */
    public synchronized Ticket open(String tenant, String id, String customerId, String subject, String body,
            int openedMinutesAgo) {
        if (!d(tenant).contracts.containsKey(customerId)) {
            throw new IllegalArgumentException("no customer " + customerId);
        }
        Ticket t = new Ticket(id, customerId, subject, body, clock.instant().minusSeconds(60L * openedMinutesAgo),
                "NEW", null, null, null, null);
        d(tenant).tickets.put(id, t);
        return t;
    }

    public synchronized Note note(String tenant, String ticketId, String id, String technicianId, String text,
            boolean internal) {
        ticket(tenant, ticketId);
        Note n = new Note(id, ticketId, technicianId, clock.instant(), text, internal);
        d(tenant).notes.computeIfAbsent(ticketId, x -> new ArrayList<>()).add(n);
        return n;
    }

    public synchronized RemoteSession session(String tenant, String ticketId, String id, String technicianId,
            int minutes) {
        ticket(tenant, ticketId);
        Instant end = clock.instant();
        RemoteSession s = new RemoteSession(id, ticketId, technicianId, end.minusSeconds(60L * minutes), end);
        d(tenant).sessions.add(s);
        return s;
    }

    public synchronized CheckIn visit(String tenant, String ticketId, String id, String technicianId, int minutes) {
        ticket(tenant, ticketId);
        Instant end = clock.instant();
        CheckIn c = new CheckIn(id, ticketId, technicianId, end.minusSeconds(60L * minutes), end);
        d(tenant).checkIns.add(c);
        return c;
    }

    public synchronized void status(String tenant, String ticketId, String status) {
        Ticket t = ticket(tenant, ticketId);
        d(tenant).tickets.put(ticketId, new Ticket(t.id(), t.customerId(), t.subject(), t.body(), t.createdAt(), status,
                t.category(), t.priority(), t.assignee(), "RESOLVED".equals(status) ? clock.instant() : t.closedAt()));
    }

    public synchronized List<Invoice> invoices(String tenant) {
        return List.copyOf(d(tenant).invoices);
    }

    public synchronized List<String[]> updates(String tenant) {
        return List.copyOf(d(tenant).updates);
    }

    public synchronized List<String> customers(String tenant) {
        return List.copyOf(d(tenant).contracts.keySet());
    }

    // --- the ports ---

    @Override
    public synchronized Ticket ticket(String tenant, String ticketId) {
        Ticket t = d(tenant).tickets.get(ticketId);
        if (t == null) {
            throw new mspagent.core.engine.PermanentFailure("no ticket " + ticketId);
        }
        return t;
    }

    @Override
    public synchronized List<Note> notes(String tenant, String ticketId) {
        return List.copyOf(d(tenant).notes.getOrDefault(ticketId, List.of()));
    }

    @Override
    public synchronized List<TimeEntry> entries(String tenant, String ticketId) {
        return d(tenant).entries.values().stream().filter(e -> e.ticketId().equals(ticketId)).toList();
    }

    @Override
    public synchronized List<TimeEntry> confirmedEntries(String tenant, String customerId, String month) {
        return d(tenant).entries.values().stream()
                .filter(e -> e.customerId().equals(customerId) && e.status() == EntryStatus.CONFIRMED)
                .filter(e -> YearMonth.from(e.start().atZone(BERLIN)).toString().equals(month))
                .toList();
    }

    @Override
    public synchronized List<Technician> technicians(String tenant) {
        Map<String, Integer> open = new HashMap<>();
        d(tenant).tickets.values().stream().filter(t -> t.assignee() != null && t.closedAt() == null)
                .forEach(t -> open.merge(t.assignee(), 1, Integer::sum));
        return d(tenant).technicians.stream()
                .map(t -> new Technician(t.id(), t.name(), t.skills(), open.getOrDefault(t.id(), 0))).toList();
    }

    @Override
    public synchronized void applyTriage(String tenant, TriageDecision x) {
        Ticket t = ticket(tenant, x.ticketId());
        d(tenant).tickets.put(t.id(), new Ticket(t.id(), t.customerId(), t.subject(), t.body(), t.createdAt(),
                t.status(), x.category(), x.priority(), x.assignee(), t.closedAt()));
        d(tenant).refs.add(x.externalRef());
    }

    @Override
    public synchronized void addEntry(String tenant, TimeEntry e) {
        d(tenant).entries.put(e.id(), e);
        d(tenant).refs.add(e.externalRef());
    }

    @Override
    public synchronized void voidEntry(String tenant, String entryId, String externalRef) {
        d(tenant).entries.computeIfPresent(entryId, (k, e) -> e.withStatus(EntryStatus.VOID));
        d(tenant).refs.add(externalRef);
    }

    @Override
    public synchronized void postUpdate(String tenant, String ticketId, String text, String externalRef) {
        d(tenant).updates.add(new String[] {ticketId, text});
        d(tenant).refs.add(externalRef);
    }

    @Override
    public synchronized boolean exists(String tenant, String externalRef) {
        return d(tenant).refs.contains(externalRef);
    }

    @Override
    public synchronized List<RemoteSession> sessions(String tenant, String ticketId) {
        return d(tenant).sessions.stream().filter(s -> s.ticketId().equals(ticketId)).toList();
    }

    @Override
    public synchronized List<CheckIn> checkIns(String tenant, String ticketId) {
        return d(tenant).checkIns.stream().filter(s -> s.ticketId().equals(ticketId)).toList();
    }

    @Override
    public synchronized Contract contract(String tenant, String customerId) {
        Contract c = d(tenant).contracts.get(customerId);
        if (c == null) {
            throw new mspagent.core.engine.PermanentFailure("no contract for " + customerId);
        }
        return c;
    }

    @Override
    public synchronized void createInvoice(String tenant, Invoice invoice) {
        d(tenant).invoices.add(invoice);
        d(tenant).refs.add(invoice.externalRef());
    }
}
