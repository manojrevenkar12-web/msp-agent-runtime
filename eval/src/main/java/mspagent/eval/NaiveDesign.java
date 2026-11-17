package mspagent.eval;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mspagent.core.domain.BillableEntry;
import mspagent.core.domain.BillingRules;
import mspagent.core.domain.Category;
import mspagent.core.domain.Contract;
import mspagent.core.domain.EntrySource;
import mspagent.core.domain.EntryStatus;
import mspagent.core.domain.Invoice;
import mspagent.core.domain.Priority;
import mspagent.core.domain.TimeEntry;
import mspagent.core.domain.WorkKind;
import mspagent.core.event.Event;
import mspagent.core.event.EventTypes;
import mspagent.core.port.LlmUnavailable;
import mspagent.core.port.Note;
import mspagent.core.port.NoteInterval;
import mspagent.core.port.Technician;
import mspagent.core.port.Ticket;
import mspagent.core.port.TriageDecision;
import mspagent.core.port.TriageReading;
import mspagent.core.port.UpdateRequest;
import mspagent.world.Rand;
import mspagent.world.SimLlm;
import mspagent.world.World;

/**
 * The common first build: the same model and the same events, but the model's answer is acted on directly, every
 * delivery runs the whole handler, and writes carry no idempotency key. A worker that dies mid-handler leaves its
 * earlier writes in place and the redelivery makes them again.
 */
final class NaiveDesign implements Timeline.Consumer {
    private final World w;
    private final SimLlm llm;
    private int counter;
    int crashes;

    NaiveDesign(World w, SimLlm llm) {
        this.w = w;
        this.llm = llm;
    }

    private void maybeCrash(Event e, int write, int delivery) {
        if (Rand.of(w.seed, "naive-crash|" + e.key() + "|" + write + "|" + delivery)
                .chance(w.settings.delivery().crash() / 2)) {
            throw new IllegalStateException("worker died");
        }
    }

    private final java.util.Map<String, Integer> seen = new java.util.HashMap<>();

    @Override
    public void deliver(Event e, Timeline t) {
        int delivery = seen.merge(e.key(), 1, Integer::sum);
        try {
            handle(e, t, delivery);
        } catch (LlmUnavailable x) {
            t.schedule(this, e, t.clock.instant().plusSeconds(60));
        } catch (IllegalStateException x) {
            crashes++;
            t.schedule(this, e, t.clock.instant().plusSeconds(360));
        }
    }

    private void handle(Event e, Timeline t, int delivery) {
        String tenant = e.tenant();
        switch (e.type()) {
            case EventTypes.TICKET_CREATED -> {
                Ticket tk = t.sys.ticket(tenant, e.get("ticketId"));
                TriageReading r = llm.readTicket(tk.subject(), tk.body()).value();
                Priority p = r.impact() == null || r.urgency() == null ? Priority.P3
                        : Priority.of(r.impact(), r.urgency());
                Category c = r.category() == null ? Category.INCIDENT : r.category();
                String tech = t.sys.technicians(tenant).stream().filter(x -> x.skills().contains(c))
                        .min(Comparator.comparingInt(Technician::openTickets).thenComparing(Technician::id))
                        .map(Technician::id).orElse(null);
                maybeCrash(e, 1, delivery);
                t.sys.applyTriage(tenant, new TriageDecision(tk.id(), p, c, tech, false, "model", "triage:" + tk.id()));
            }
            case EventTypes.NOTE_ADDED -> {
                String ticketId = e.get("ticketId");
                Ticket tk = t.sys.ticket(tenant, ticketId);
                Note n = t.sys.notes(tenant, ticketId).stream().filter(x -> x.id().equals(e.get("noteId")))
                        .findFirst().orElseThrow();
                int i = 0;
                for (NoteInterval iv : llm.readNote(n.text()).value().intervals()) {
                    Instant start = iv.startMinuteOfDay() == null ? n.writtenAt().minusSeconds(60L * iv.minutes())
                            : LocalDate.ofInstant(n.writtenAt(), World.BERLIN).atStartOfDay(World.BERLIN)
                                    .plusMinutes(iv.startMinuteOfDay()).toInstant();
                    maybeCrash(e, ++i, delivery);
                    add(t, new TimeEntry("naive-" + (++counter), ticketId, tk.customerId(), n.technicianId(), start,
                            iv.minutes(), iv.kind(), EntrySource.NOTE, EntryStatus.CONFIRMED, null));
                }
            }
            case EventTypes.SESSION_ENDED -> t.sys.sessions(tenant, e.get("ticketId")).stream()
                    .filter(s -> s.id().equals(e.get("sessionId"))).findFirst().ifPresent(s -> {
                        Ticket tk = t.sys.ticket(tenant, s.ticketId());
                        add(t, new TimeEntry("naive-" + (++counter), s.ticketId(), tk.customerId(), s.technicianId(),
                                s.start(), minutes(s.start(), s.end()), WorkKind.REMOTE, EntrySource.RMM,
                                EntryStatus.CONFIRMED, null));
                        maybeCrash(e, 1, delivery);
                    });
            case EventTypes.CHECKED_OUT -> t.sys.checkIns(tenant, e.get("ticketId")).stream()
                    .filter(s -> s.id().equals(e.get("checkInId"))).findFirst().ifPresent(s -> {
                        Ticket tk = t.sys.ticket(tenant, s.ticketId());
                        add(t, new TimeEntry("naive-" + (++counter), s.ticketId(), tk.customerId(), s.technicianId(),
                                s.start(), minutes(s.start(), s.end()), WorkKind.ONSITE, EntrySource.CHECKIN,
                                EntryStatus.CONFIRMED, null));
                        maybeCrash(e, 1, delivery);
                    });
            case EventTypes.TICKET_STATUS -> {
                String ticketId = e.get("ticketId");
                Ticket tk = t.sys.ticket(tenant, ticketId);
                String name = t.sys.technicians(tenant).stream().filter(x -> x.id().equals(tk.assignee()))
                        .map(Technician::name).findFirst().orElse("unser Team");
                String text = llm.draftUpdate(new UpdateRequest(ticketId, tk.subject(), e.get("status"), name,
                        t.sys.notes(tenant, ticketId))).value();
                t.sys.postUpdate(tenant, ticketId, text, "update:" + ticketId + ":" + e.get("status"));
                maybeCrash(e, 1, delivery);
            }
            case EventTypes.MONTH_CLOSED -> {
                Contract c = t.sys.contract(tenant, e.get("customerId"));
                List<BillableEntry> b = new ArrayList<>();
                for (TimeEntry x : t.sys.confirmedEntries(tenant, c.customerId(), e.get("month"))) {
                    Ticket tk = t.sys.ticket(tenant, x.ticketId());
                    b.add(new BillableEntry(x.ticketId(), x.technicianId(), x.start(), x.minutes(), x.kind(),
                            tk.category() == null ? Category.SERVICE_REQUEST : tk.category()));
                }
                t.sys.createInvoice(tenant, new Invoice(c.customerId(), e.get("month"), BillingRules.lines(c, b),
                        "invoice:" + c.customerId() + ":" + e.get("month")));
                maybeCrash(e, 1, delivery);
            }
            default -> {
            }
        }
    }

    /** A reasonable first build skips an entry that overlaps one already on the ticket at the same time. */
    private static void add(Timeline t, TimeEntry e) {
        boolean overlaps = t.sys.entries(e.customerId(), e.ticketId()).stream()
                .filter(x -> x.status() != EntryStatus.VOID && x.technicianId().equals(e.technicianId()))
                .anyMatch(x -> x.overlapMinutes(e.start(), e.end()) * 2 >= Math.min(x.minutes(), e.minutes()));
        if (!overlaps) {
            t.sys.addEntry(e.customerId(), e);
        }
    }

    private static int minutes(Instant a, Instant b) {
        return (int) Math.max(1, (b.getEpochSecond() - a.getEpochSecond() + 59) / 60);
    }
}
