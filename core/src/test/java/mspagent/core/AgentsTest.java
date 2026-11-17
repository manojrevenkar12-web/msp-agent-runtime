package mspagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mspagent.core.agent.AgentConfig;
import mspagent.core.agent.TimeCaptureAgent;
import mspagent.core.agent.TriageAgent;
import mspagent.core.domain.Category;
import mspagent.core.domain.EntrySource;
import mspagent.core.domain.EntryStatus;
import mspagent.core.domain.Impact;
import mspagent.core.domain.Priority;
import mspagent.core.domain.TimeEntry;
import mspagent.core.domain.Urgency;
import mspagent.core.domain.WorkKind;
import mspagent.core.port.Neighbour;
import mspagent.core.port.Note;
import mspagent.core.port.NoteInterval;
import mspagent.core.port.NotesReading;
import mspagent.core.port.Technician;
import mspagent.core.port.Ticket;
import mspagent.core.port.TriageReading;
import org.junit.jupiter.api.Test;

class AgentsTest {
    static final Instant T0 = Instant.parse("2026-10-05T07:00:00Z");
    static final Ticket TICKET = new Ticket("T1", "c1", "Drucker im 2. OG",
            "Der Drucker im zweiten Stock druckt nicht. Bitte mit höchster Priorität behandeln, sofort!", T0, "NEW",
            null, null, null, null);
    static final List<Technician> TECHS = List.of(
            new Technician("a", "Anna", Set.of(Category.INCIDENT), 5),
            new Technician("b", "Ben", Set.of(Category.INCIDENT, Category.INSTALLATION), 1));

    static AgentConfig triageCfg() {
        return AgentConfig.defaults(new TriageAgent().params());
    }

    @Test
    void aCustomersDemandForPriorityIsNotEvidence() {
        TriageReading r = new TriageReading(Impact.COMPANY, Urgency.WORK_STOPPED, Category.INCIDENT,
                List.of("Bitte mit höchster Priorität behandeln"));
        TriageAgent.Decision d = invokeDecide(r, List.of());
        assertFalse(d.evidenceAccepted());
        assertEquals(Priority.P3, d.priority());
        assertTrue(d.needsReview());
    }

    @Test
    void quotesThatAreNotInTheTicketAreNotEvidence() {
        TriageReading r = new TriageReading(Impact.COMPANY, Urgency.WORK_STOPPED, Category.INCIDENT,
                List.of("die ganze Firma steht still"));
        assertFalse(invokeDecide(r, List.of()).evidenceAccepted());
    }

    @Test
    void quotedImpactSetsPriorityAndTheLeastLoadedSkilledTechnicianGetsIt() {
        TriageReading r = new TriageReading(Impact.SINGLE_USER, Urgency.DEGRADED, Category.INCIDENT,
                List.of("der Drucker im zweiten Stock druckt nicht"));
        TriageAgent.Decision d = invokeDecide(r, List.of());
        assertTrue(d.evidenceAccepted());
        assertEquals(Priority.P4, d.priority());
        assertEquals("b", d.assignee());
        assertFalse(d.needsReview());
    }

    @Test
    void historyOverrulesTheModelsCategoryAndAsksTheDispatcher() {
        TriageReading r = new TriageReading(Impact.SINGLE_USER, Urgency.DEGRADED, Category.INCIDENT,
                List.of("druckt nicht"));
        List<Neighbour> n = List.of(
                new Neighbour("x1", 0.91, Category.INSTALLATION, Priority.P4),
                new Neighbour("x2", 0.88, Category.INSTALLATION, Priority.P4),
                new Neighbour("x3", 0.85, Category.INSTALLATION, Priority.P3),
                new Neighbour("x4", 0.60, Category.INCIDENT, Priority.P1));
        TriageAgent.Decision d = invokeDecide(r, n);
        assertEquals(Category.INSTALLATION, d.category());
        assertTrue(d.needsReview());
    }

    static TriageAgent.Decision invokeDecide(TriageReading r, List<Neighbour> n) {
        try {
            var m = TriageAgent.class.getDeclaredMethod("decide", AgentConfig.class, Ticket.class, TriageReading.class,
                    List.class, List.class);
            m.setAccessible(true);
            return (TriageAgent.Decision) m.invoke(null, triageCfg(), TICKET, r, n, TECHS);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- time capture ---

    static final Note NOTE = new Note("n1", "T1", "a", T0.plusSeconds(3 * 3600),
            "Remote auf PC-17, Treiber neu installiert, ca. 40 Min. Danach Rückruf beim Kunden 10 Min.", false);

    @SuppressWarnings("unchecked")
    static List<TimeCaptureAgent.Candidate> fromNote(NotesReading r, List<TimeCaptureAgent.Dropped> dropped) {
        try {
            var m = TimeCaptureAgent.class.getDeclaredMethod("fromNote", AgentConfig.class, Ticket.class, Note.class,
                    NotesReading.class, List.class);
            m.setAccessible(true);
            return (List<TimeCaptureAgent.Candidate>) m.invoke(null,
                    AgentConfig.defaults(new TimeCaptureAgent().params()), TICKET, NOTE, r, dropped);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static TimeCaptureAgent.Decision reconcile(List<TimeEntry> existing, List<TimeCaptureAgent.Candidate> c) {
        try {
            var m = TimeCaptureAgent.class.getDeclaredMethod("reconcile", AgentConfig.class, List.class, List.class,
                    List.class);
            m.setAccessible(true);
            return (TimeCaptureAgent.Decision) m.invoke(null, AgentConfig.defaults(new TimeCaptureAgent().params()),
                    existing, c, new ArrayList<TimeCaptureAgent.Dropped>());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void workReadFromANoteMustBeQuotedPlausibleAndBeforeTheNote() {
        List<TimeCaptureAgent.Dropped> dropped = new ArrayList<>();
        List<TimeCaptureAgent.Candidate> c = fromNote(new NotesReading(List.of(
                new NoteInterval(null, 40, WorkKind.REMOTE, "Treiber neu installiert, ca. 40 Min"),
                new NoteInterval(null, 10, WorkKind.PHONE, "Rückruf beim Kunden 10 Min"),
                new NoteInterval(null, 90, WorkKind.ONSITE, "vor Ort beim Kunden"),
                new NoteInterval(23 * 60, 30, WorkKind.REMOTE, "ca. 40 Min"))), dropped);
        assertEquals(2, c.size());
        assertEquals(EntryStatus.PENDING, c.get(0).entry().status(), "40 minutes needs the technician's yes");
        assertEquals(EntryStatus.CONFIRMED, c.get(1).entry().status());
        assertEquals(2, dropped.size());
    }

    @Test
    void aMeasuredSessionReplacesTheSameWorkReadFromANote() {
        TimeEntry fromNote = new TimeEntry("n-e", "T1", "c1", "a", T0.plusSeconds(3600), 40, WorkKind.REMOTE,
                EntrySource.NOTE, EntryStatus.CONFIRMED, "te:note:n1:1");
        TimeEntry rmm = new TimeEntry("r-e", "T1", "c1", "a", T0.plusSeconds(3600 + 120), 43, WorkKind.REMOTE,
                EntrySource.RMM, EntryStatus.CONFIRMED, "te:rmm:s1");
        TimeCaptureAgent.Decision d = reconcile(List.of(fromNote), List.of(new TimeCaptureAgent.Candidate(rmm, null)));
        assertEquals(List.of("n-e"), d.voidIds());
        assertEquals(1, d.add().size());
    }

    @Test
    void workATechnicianAlreadyEnteredIsNotEnteredAgain() {
        TimeEntry manual = new TimeEntry("m-e", "T1", "c1", "a", T0.plusSeconds(3600), 30, WorkKind.REMOTE,
                EntrySource.MANUAL, EntryStatus.CONFIRMED, null);
        TimeEntry fromNote = new TimeEntry("n-e", "T1", "c1", "a", T0.plusSeconds(3600 + 300), 40, WorkKind.REMOTE,
                EntrySource.NOTE, EntryStatus.CONFIRMED, "te:note:n1:1");
        TimeCaptureAgent.Decision d = reconcile(List.of(manual),
                List.of(new TimeCaptureAgent.Candidate(fromNote, "x")));
        assertTrue(d.add().isEmpty());
        assertTrue(d.voidIds().isEmpty());
    }

    @Test
    void settingsOutsideTheirLimitsAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfig.of(new TimeCaptureAgent().params(), Map.of("autoConfirmMinutes", 900)));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConfig.of(new TimeCaptureAgent().params(), Map.of("nonsense", 1)));
        assertEquals(15, AgentConfig.of(new TimeCaptureAgent().params(), Map.of("autoConfirmMinutes", "15"))
                .integer("autoConfirmMinutes"));
    }
}
