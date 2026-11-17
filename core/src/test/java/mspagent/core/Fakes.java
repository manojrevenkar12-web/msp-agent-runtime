package mspagent.core;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import mspagent.core.domain.Contract;
import mspagent.core.domain.EntryStatus;
import mspagent.core.domain.Invoice;
import mspagent.core.domain.TimeEntry;
import mspagent.core.port.CheckIn;
import mspagent.core.port.Erp;
import mspagent.core.port.FieldApp;
import mspagent.core.port.Llm;
import mspagent.core.port.LlmCall;
import mspagent.core.port.Neighbour;
import mspagent.core.port.Note;
import mspagent.core.port.NotesReading;
import mspagent.core.port.RemoteSession;
import mspagent.core.port.Rmm;
import mspagent.core.port.SimilarTickets;
import mspagent.core.port.Technician;
import mspagent.core.port.Ticket;
import mspagent.core.port.TicketSystem;
import mspagent.core.port.Tools;
import mspagent.core.port.TriageDecision;
import mspagent.core.port.TriageReading;
import mspagent.core.port.UpdateRequest;

/** Small in-memory outside systems for the core tests. They count every write. */
public final class Fakes {
    private Fakes() {}

    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(Instant now) {
            this.now = now;
        }

        public void advanceMinutes(long m) {
            now = now.plusSeconds(60 * m);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    public static final class Tickets implements TicketSystem {
        public final Map<String, Ticket> tickets = new LinkedHashMap<>();
        public final Map<String, List<Note>> notes = new HashMap<>();
        public final Map<String, TimeEntry> entries = new LinkedHashMap<>();
        public final List<Technician> techs = new ArrayList<>();
        public final Set<String> refs = new HashSet<>();
        public final List<TriageDecision> triage = new ArrayList<>();
        public final List<String> updates = new ArrayList<>();
        public int writes;

        @Override
        public Ticket ticket(String tenant, String id) {
            return tickets.get(id);
        }

        @Override
        public List<Note> notes(String tenant, String id) {
            return notes.getOrDefault(id, List.of());
        }

        @Override
        public List<TimeEntry> entries(String tenant, String id) {
            return entries.values().stream().filter(e -> e.ticketId().equals(id)).toList();
        }

        @Override
        public List<TimeEntry> confirmedEntries(String tenant, String customerId, String month) {
            return entries.values().stream()
                    .filter(e -> e.customerId().equals(customerId) && e.status() == EntryStatus.CONFIRMED)
                    .toList();
        }

        @Override
        public List<Technician> technicians(String tenant) {
            return techs;
        }

        @Override
        public void applyTriage(String tenant, TriageDecision d) {
            writes++;
            triage.add(d);
            refs.add(d.externalRef());
            Ticket t = tickets.get(d.ticketId());
            tickets.put(t.id(), new Ticket(t.id(), t.customerId(), t.subject(), t.body(), t.createdAt(), t.status(),
                    d.category(), d.priority(), d.assignee(), t.closedAt()));
        }

        @Override
        public void addEntry(String tenant, TimeEntry e) {
            writes++;
            entries.put(e.id(), e);
            refs.add(e.externalRef());
        }

        @Override
        public void voidEntry(String tenant, String entryId, String ref) {
            writes++;
            entries.computeIfPresent(entryId, (k, e) -> e.withStatus(EntryStatus.VOID));
            refs.add(ref);
        }

        @Override
        public void postUpdate(String tenant, String ticketId, String text, String ref) {
            writes++;
            updates.add(text);
            refs.add(ref);
        }

        @Override
        public boolean exists(String tenant, String ref) {
            return refs.contains(ref);
        }
    }

    public static final class Systems implements Rmm, FieldApp, Erp, SimilarTickets {
        public final List<RemoteSession> sessions = new ArrayList<>();
        public final List<CheckIn> checkIns = new ArrayList<>();
        public final Map<String, Contract> contracts = new HashMap<>();
        public final List<Invoice> invoices = new ArrayList<>();
        public final List<Neighbour> neighbours = new ArrayList<>();

        @Override
        public List<RemoteSession> sessions(String tenant, String ticketId) {
            return sessions.stream().filter(s -> s.ticketId().equals(ticketId)).toList();
        }

        @Override
        public List<CheckIn> checkIns(String tenant, String ticketId) {
            return checkIns.stream().filter(s -> s.ticketId().equals(ticketId)).toList();
        }

        @Override
        public Contract contract(String tenant, String customerId) {
            return contracts.get(customerId);
        }

        @Override
        public void createInvoice(String tenant, Invoice invoice) {
            invoices.add(invoice);
        }

        @Override
        public boolean exists(String tenant, String ref) {
            return invoices.stream().anyMatch(i -> i.externalRef().equals(ref));
        }

        @Override
        public List<Neighbour> nearest(String tenant, String text, int k) {
            return neighbours;
        }
    }

    /** A model that answers from functions set by the test, and can be made unavailable. */
    public static final class ScriptedLlm implements Llm {
        public Function<String, TriageReading> triage = s -> null;
        public Function<String, NotesReading> notes = s -> new NotesReading(List.of());
        public Function<UpdateRequest, String> update = r -> "Update: " + r.subject();
        public int calls;
        public int failNext;

        private void maybeFail() {
            calls++;
            if (failNext > 0) {
                failNext--;
                throw new mspagent.core.port.LlmUnavailable("429 from the provider");
            }
        }

        @Override
        public LlmCall<TriageReading> readTicket(String subject, String body) {
            maybeFail();
            return new LlmCall<>(triage.apply(subject + "\n" + body), "test-model", 400, 80);
        }

        @Override
        public LlmCall<NotesReading> readNote(String text) {
            maybeFail();
            return new LlmCall<>(notes.apply(text), "test-model", 200, 60);
        }

        @Override
        public LlmCall<String> draftUpdate(UpdateRequest r) {
            maybeFail();
            return new LlmCall<>(update.apply(r), "test-model", 300, 90);
        }
    }

    public static Tools tools(Tickets t, Systems s, ScriptedLlm llm) {
        return new Tools(t, s, s, s, llm, s);
    }
}
