package mspagent.world;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mspagent.core.domain.Category;
import mspagent.core.domain.Contract;
import mspagent.core.domain.EntrySource;
import mspagent.core.domain.EntryStatus;
import mspagent.core.domain.Invoice;
import mspagent.core.domain.Priority;
import mspagent.core.domain.TimeEntry;
import mspagent.core.port.CheckIn;
import mspagent.core.port.Erp;
import mspagent.core.port.FieldApp;
import mspagent.core.port.Neighbour;
import mspagent.core.port.Note;
import mspagent.core.port.RemoteSession;
import mspagent.core.port.Rmm;
import mspagent.core.port.SimilarTickets;
import mspagent.core.port.Technician;
import mspagent.core.port.Ticket;
import mspagent.core.port.TicketSystem;
import mspagent.core.port.Tools;
import mspagent.core.port.TriageDecision;
import mspagent.world.Truth.HistoryTicket;
import mspagent.world.Truth.ManualPlan;
import mspagent.world.Truth.StatusChange;
import mspagent.world.Truth.TrueNote;
import mspagent.world.Truth.TrueSession;
import mspagent.world.Truth.TrueTicket;

/**
 * The service provider's systems as one design meets them: ticket system, RMM, field app, ERP and ticket history.
 * They show only what has happened by the clock's time, record every write, and count writes that repeat.
 */
public final class WorldSystems implements TicketSystem, Rmm, FieldApp, Erp, SimilarTickets {
    private final World w;
    private final Clock clock;
    private final Map<String, Ticket> tickets = new LinkedHashMap<>();
    private final Map<String, List<Note>> notes = new HashMap<>();
    private final List<RemoteSession> rmm = new ArrayList<>();
    private final List<CheckIn> checkIns = new ArrayList<>();
    private final Map<String, TimeEntry> entries = new LinkedHashMap<>();
    private final Set<String> refs = new HashSet<>();
    public final Map<String, TriageRecord> triage = new HashMap<>();
    public final List<Update> updates = new ArrayList<>();
    public final List<Invoice> invoices = new ArrayList<>();
    public int duplicateTriageWrites;
    public int duplicateEntryWrites;
    public int duplicateUpdates;
    public int duplicateInvoices;
    public int manualEntriesTyped;
    public int confirmationsAsked;
    public int dispatcherReviews;

    public record TriageRecord(TriageDecision decision, Instant at, Instant reviewAt, Priority finalPriority,
            Category finalCategory) {}

    public record Update(String ticketId, String text, Instant at, String ref) {}

    public WorldSystems(World w, Clock clock) {
        this.w = w;
        this.clock = clock;
    }

    public Tools tools(SimLlm llm) {
        return new Tools(this, this, this, this, llm, this);
    }

    // --- what happens in the world ---

    public void open(TrueTicket t) {
        tickets.put(t.id(), new Ticket(t.id(), t.customerId(), t.subject(), t.body(), t.createdAt(), "NEW", null, null,
                null, null));
    }

    public void write(TrueNote n) {
        notes.computeIfAbsent(n.ticketId(), x -> new ArrayList<>())
                .add(new Note(n.id(), n.ticketId(), n.technicianId(), n.writtenAt(), n.text(), n.internal()));
    }

    public void end(TrueSession s) {
        if (s.viaRmm()) {
            rmm.add(new RemoteSession(s.id(), s.ticketId(), s.technicianId(), s.start(), s.end()));
        }
        if (s.checkedIn()) {
            checkIns.add(new CheckIn(s.id(), s.ticketId(), s.technicianId(), s.start(), s.end()));
        }
    }

    public void status(StatusChange c) {
        Ticket t = tickets.get(c.ticketId());
        Instant closed = c.status().equals("RESOLVED") ? c.at() : t.closedAt();
        tickets.put(t.id(), new Ticket(t.id(), t.customerId(), t.subject(), t.body(), t.createdAt(), c.status(),
                t.category(), t.priority(), t.assignee(), closed));
    }

    /**
     * A technician types time, unless the ticket already shows an entry of theirs for that work (one starting within
     * 90 minutes of it).
     */
    public boolean typeManual(ManualPlan p) {
        boolean covered = entries.values().stream()
                .filter(e -> e.status() != EntryStatus.VOID && e.ticketId().equals(p.ticketId())
                        && e.technicianId().equals(p.technicianId()))
                .anyMatch(e -> Math.abs(e.start().getEpochSecond() - p.start().getEpochSecond()) <= 90 * 60);
        if (covered) {
            return false;
        }
        String id = "manual-" + p.sessionId();
        entries.put(id, new TimeEntry(id, p.ticketId(), p.customerId(), p.technicianId(), p.start(), p.minutes(),
                p.kind(), EntrySource.MANUAL, EntryStatus.CONFIRMED, null));
        manualEntriesTyped++;
        return true;
    }

    /** Before billing, technicians answer the entries that wait for them. */
    public void confirmPending() {
        WorldSettings.People pp = w.settings.people();
        for (TimeEntry e : List.copyOf(entries.values())) {
            if (e.status() != EntryStatus.PENDING) {
                continue;
            }
            confirmationsAsked++;
            boolean right = Scoring.matchesWork(w, e) != null && !Scoring.alreadyCovered(w, e, this);
            Rand r = Rand.of(w.seed, "confirm|" + e.id());
            boolean confirm = right ? r.chance(pp.technicianConfirmsRight()) : !r.chance(pp.technicianRejectsWrong());
            entries.put(e.id(), e.withStatus(confirm ? EntryStatus.CONFIRMED : EntryStatus.VOID));
        }
    }

    /** The dispatcher sets priority, category and technician by hand (the status quo). */
    public void dispatch(TrueTicket t, Instant at) {
        WorldSettings.People pp = w.settings.people();
        Rand r = Rand.of(w.seed, "dispatch|" + t.id());
        Priority p = r.chance(pp.dispatcherReadsRight()) ? t.priority() : Priority.values()[r.between(0, 3)];
        if (t.injection() != null && r.chance(pp.dispatcherPushedByCustomer())) {
            p = Priority.P1;
        }
        Category c = r.chance(pp.dispatcherCategoryRight()) ? t.category() : t.confusable();
        String tech = leastLoaded(c);
        TriageDecision d = new TriageDecision(t.id(), p, c, tech, false, "dispatcher", "dispatch:" + t.id());
        set(t.id(), d);
        triage.put(t.id(), new TriageRecord(d, at, null, p, c));
    }

    public Instant dispatcherTime(Instant created) {
        Rand r = Rand.of(w.seed, "dispatcher-delay|" + created);
        ZonedDateTime z = created.atZone(World.BERLIN);
        boolean weekend = z.getDayOfWeek().getValue() >= 6;
        int m = z.getHour() * 60 + z.getMinute();
        ZonedDateTime start = z;
        if (weekend || m >= 17 * 60) {
            start = z.plusDays(weekend && z.getDayOfWeek().getValue() == 6 ? 2 : 1).with(LocalTime.of(8, 0));
            while (start.getDayOfWeek().getValue() >= 6) {
                start = start.plusDays(1);
            }
        } else if (m < 8 * 60) {
            start = z.with(LocalTime.of(8, 0));
        }
        return start.toInstant().plusSeconds(60L * r.between(w.settings.sla().dispatcherDelayMinutes()));
    }

    private String leastLoaded(Category c) {
        return technicians(w.tenant).stream()
                .filter(t -> t.skills().contains(c))
                .min(java.util.Comparator.comparingInt(Technician::openTickets).thenComparing(Technician::id))
                .map(Technician::id)
                .orElse(null);
    }

    private void set(String ticketId, TriageDecision d) {
        Ticket t = tickets.get(ticketId);
        tickets.put(ticketId, new Ticket(t.id(), t.customerId(), t.subject(), t.body(), t.createdAt(), t.status(),
                d.category(), d.priority(), d.assignee(), t.closedAt()));
    }

    // --- the ports ---

    @Override
    public Ticket ticket(String tenant, String ticketId) {
        Ticket t = tickets.get(ticketId);
        if (t == null) {
            throw new IllegalArgumentException("no ticket " + ticketId);
        }
        return t;
    }

    @Override
    public List<Note> notes(String tenant, String ticketId) {
        return List.copyOf(notes.getOrDefault(ticketId, List.of()));
    }

    @Override
    public List<TimeEntry> entries(String tenant, String ticketId) {
        return entries.values().stream().filter(e -> e.ticketId().equals(ticketId)).toList();
    }

    public List<TimeEntry> allEntries() {
        return List.copyOf(entries.values());
    }

    @Override
    public List<TimeEntry> confirmedEntries(String tenant, String customerId, String month) {
        return entries.values().stream()
                .filter(e -> e.customerId().equals(customerId) && e.status() == EntryStatus.CONFIRMED)
                .filter(e -> World.monthOf(e.start()).equals(month))
                .toList();
    }

    @Override
    public List<Technician> technicians(String tenant) {
        Map<String, Integer> open = new HashMap<>();
        for (Ticket t : tickets.values()) {
            if (t.assignee() != null && t.closedAt() == null) {
                open.merge(t.assignee(), 1, Integer::sum);
            }
        }
        return w.technicians.stream()
                .map(t -> new Technician(t.id(), t.name(), t.skills(), open.getOrDefault(t.id(), 0)))
                .toList();
    }

    @Override
    public void applyTriage(String tenant, TriageDecision d) {
        if (triage.containsKey(d.ticketId())) {
            duplicateTriageWrites++;
            return;
        }
        refs.add(d.externalRef());
        TrueTicket truth = w.ticketById.get(d.ticketId());
        Instant now = clock.instant();
        Priority fp = d.priority();
        Category fc = d.category();
        Instant reviewAt = null;
        if (d.needsReview()) {
            dispatcherReviews++;
            WorldSettings.People pp = w.settings.people();
            Rand r = Rand.of(w.seed, "review|" + d.ticketId());
            reviewAt = now.plusSeconds(60L * r.between(w.settings.sla().reviewDelayMinutes()));
            if (r.chance(pp.reviewFixesPriority())) {
                fp = truth.priority();
            }
            if (r.chance(pp.dispatcherCategoryRight())) {
                fc = truth.category();
            }
        }
        TriageDecision applied = new TriageDecision(d.ticketId(), fp, fc, d.assignee(), d.needsReview(), d.reason(),
                d.externalRef());
        set(d.ticketId(), applied);
        triage.put(d.ticketId(), new TriageRecord(d, now, reviewAt, fp, fc));
    }

    @Override
    public void addEntry(String tenant, TimeEntry e) {
        if (e.externalRef() != null && refs.contains(e.externalRef())) {
            duplicateEntryWrites++;
        }
        if (entries.containsKey(e.id())) {
            duplicateEntryWrites++;
        }
        if (e.externalRef() != null) {
            refs.add(e.externalRef());
        }
        entries.put(e.id(), e);
    }

    @Override
    public void voidEntry(String tenant, String entryId, String externalRef) {
        refs.add(externalRef);
        TimeEntry e = entries.get(entryId);
        if (e != null) {
            entries.put(entryId, e.withStatus(EntryStatus.VOID));
        }
    }

    @Override
    public void postUpdate(String tenant, String ticketId, String text, String externalRef) {
        if (refs.contains(externalRef)) {
            duplicateUpdates++;
        }
        refs.add(externalRef);
        updates.add(new Update(ticketId, text, clock.instant(), externalRef));
    }

    @Override
    public boolean exists(String tenant, String externalRef) {
        return refs.contains(externalRef);
    }

    @Override
    public List<RemoteSession> sessions(String tenant, String ticketId) {
        return rmm.stream().filter(s -> s.ticketId().equals(ticketId)).toList();
    }

    @Override
    public List<CheckIn> checkIns(String tenant, String ticketId) {
        return checkIns.stream().filter(s -> s.ticketId().equals(ticketId)).toList();
    }

    @Override
    public Contract contract(String tenant, String customerId) {
        return w.contracts.get(customerId);
    }

    @Override
    public void createInvoice(String tenant, Invoice invoice) {
        if (refs.contains(invoice.externalRef())) {
            duplicateInvoices++;
        }
        refs.add(invoice.externalRef());
        invoices.add(invoice);
    }

    @Override
    public List<Neighbour> nearest(String tenant, String text, int k) {
        TrueTicket t = w.ticketByText.get(text);
        Rand r = Rand.of(w.seed, "neighbours|" + text);
        List<Neighbour> out = new ArrayList<>();
        if (t != null) {
            List<HistoryTicket> same = w.history.get(t.templateId());
            for (int i = 0; i < Math.min(6, k); i++) {
                HistoryTicket h = r.pick(same);
                out.add(new Neighbour("H-" + t.templateId() + "-" + i, 0.81 + 0.14 * r.unit(), h.category(),
                        h.priority()));
            }
        }
        List<String> others = new ArrayList<>(w.history.keySet());
        others.sort(String::compareTo);
        while (out.size() < k) {
            String tid = r.pick(others);
            HistoryTicket h = r.pick(w.history.get(tid));
            out.add(new Neighbour("H-" + tid + "-x" + out.size(), 0.5 + 0.25 * r.unit(), h.category(), h.priority()));
        }
        return out;
    }

    public Optional<Ticket> visible(String ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }
}
