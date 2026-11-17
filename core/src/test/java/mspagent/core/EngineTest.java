package mspagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mspagent.core.agent.Agents;
import mspagent.core.domain.Category;
import mspagent.core.domain.Contract;
import mspagent.core.domain.ContractType;
import mspagent.core.domain.EntrySource;
import mspagent.core.domain.EntryStatus;
import mspagent.core.domain.Impact;
import mspagent.core.domain.SlaTier;
import mspagent.core.domain.TimeEntry;
import mspagent.core.domain.Urgency;
import mspagent.core.domain.WorkKind;
import mspagent.core.engine.AgentVersion;
import mspagent.core.engine.Approval;
import mspagent.core.engine.Engine;
import mspagent.core.engine.EngineSettings;
import mspagent.core.engine.Faults;
import mspagent.core.engine.InMemoryRuntimeStore;
import mspagent.core.engine.ModelPrice;
import mspagent.core.engine.Run;
import mspagent.core.engine.RunListener;
import mspagent.core.engine.RunStatus;
import mspagent.core.engine.RuntimeStore;
import mspagent.core.engine.SimulatedCrash;
import mspagent.core.event.Event;
import mspagent.core.event.EventTypes;
import mspagent.core.port.Technician;
import mspagent.core.port.Ticket;
import mspagent.core.port.TriageReading;
import mspagent.core.route.Router;
import mspagent.core.trace.OtlpJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineTest {
    static final String T = "acme-it";
    final Fakes.MutableClock clock = new Fakes.MutableClock(Instant.parse("2026-10-05T08:00:00Z"));
    final Fakes.Tickets tickets = new Fakes.Tickets();
    final Fakes.Systems systems = new Fakes.Systems();
    final Fakes.ScriptedLlm llm = new Fakes.ScriptedLlm();
    final RuntimeStore store = new InMemoryRuntimeStore();
    final EngineSettings settings = new EngineSettings(4, Duration.ofMinutes(1), Duration.ofMinutes(5),
            Map.of("test-model", new ModelPrice(40, 200)));

    @BeforeEach
    void setUp() {
        for (String a : List.of("triage", "time-capture", "billing", "concierge")) {
            store.saveVersion(new AgentVersion(T, a, 1, Map.of(), AgentVersion.Status.PUBLISHED, "test", clock.instant(),
                    null));
        }
        tickets.tickets.put("T1", new Ticket("T1", "c1", "Server down", "Unser Fileserver ist down, die ganze Firma "
                + "kann nicht arbeiten.", clock.instant(), "NEW", null, null, null, null));
        tickets.techs.add(new Technician("tech1", "Jonas", Set.of(Category.values()), 2));
        llm.triage = s -> new TriageReading(Impact.COMPANY, Urgency.WORK_STOPPED, Category.INCIDENT,
                List.of("die ganze Firma kann nicht arbeiten"));
        systems.contracts.put("c1", new Contract("c1", "Kanzlei Weber", ContractType.TIME_AND_MATERIAL, SlaTier.GOLD,
                120_00, 0, 0, 45_00));
    }

    Engine engine(Faults faults) {
        return new Engine(store, Router.fromAgents(Agents.builtIn()), Agents.builtIn(),
                Fakes.tools(tickets, systems, llm), clock, settings, faults, RunListener.NONE);
    }

    Event created() {
        return new Event(T, "tickets", "evt-1", EventTypes.TICKET_CREATED, clock.instant(), Map.of("ticketId", "T1"));
    }

    @Test
    void aRedeliveredEventDoesNotRunOrWriteTwice() {
        Engine e = engine(Faults.NONE);
        Engine.Outcome first = e.handle(created(), "w1");
        Engine.Outcome again = e.handle(created(), "w2");
        assertTrue(first.firstDelivery());
        assertEquals(1, first.started());
        assertFalse(again.firstDelivery());
        assertEquals(1, again.skipped());
        assertEquals(1, tickets.writes);
        assertEquals(1, llm.calls);
    }

    @Test
    void aRunThatDiesAfterAStepResumesWithoutRepeatingIt() {
        Faults dieAfterReading = (point, run, detail) -> {
            if (point.equals("after-step") && detail.equals("reading") && llm.calls == 1 && tickets.writes == 0
                    && store.steps(run).size() == 2) {
                throw new SimulatedCrash("killed after reading");
            }
        };
        Engine e = engine(dieAfterReading);
        assertThrows(SimulatedCrash.class, () -> e.handle(created(), "w1"));
        // The broker redelivers; the dead worker still holds the lease.
        assertEquals(0, e.handle(created(), "w2").started() + e.tick("w2", 10));
        clock.advanceMinutes(6);
        e.handle(created(), "w2");
        assertEquals(1, llm.calls, "the model is not asked again");
        assertEquals(1, tickets.writes);
        Run r = store.runs(T, RuntimeStore.RunQuery.latest(10)).get(0);
        assertEquals(RunStatus.DONE, r.status());
        assertEquals(2, r.attempts());
    }

    @Test
    void aWriteIsNotRepeatedWhenTheWorkerDiedBeforeRecordingIt() {
        boolean[] died = {false};
        Faults dieAfterCall = (point, run, detail) -> {
            if (point.equals("after-call") && !died[0]) {
                died[0] = true;
                throw new SimulatedCrash("killed between the write and its record");
            }
        };
        Engine e = engine(dieAfterCall);
        assertThrows(SimulatedCrash.class, () -> e.handle(created(), "w1"));
        assertEquals(1, tickets.writes);
        clock.advanceMinutes(6);
        e.tick("w2", 10);
        assertEquals(1, tickets.writes, "the ticket system already had the write under its reference");
        assertEquals(RunStatus.DONE, store.runs(T, RuntimeStore.RunQuery.latest(1)).get(0).status());
    }

    @Test
    void aThrottledModelIsRetriedWithBackoffAndARunGivesUpAfterTheLastAttempt() {
        llm.failNext = 1;
        Engine e = engine(Faults.NONE);
        e.handle(created(), "w1");
        Run r = store.runs(T, RuntimeStore.RunQuery.latest(1)).get(0);
        assertEquals(RunStatus.RETRY, r.status());
        assertEquals(clock.instant().plusSeconds(60), r.nextAttemptAt());
        clock.advanceMinutes(1);
        e.tick("w1", 10);
        assertEquals(RunStatus.DONE, store.runs(T, RuntimeStore.RunQuery.latest(1)).get(0).status());

        tickets.tickets.put("T2", tickets.tickets.get("T1"));
        llm.failNext = 100;
        Event other = new Event(T, "tickets", "evt-2", EventTypes.TICKET_CREATED, clock.instant(),
                Map.of("ticketId", "T2"));
        e.handle(other, "w1");
        for (int i = 0; i < 10; i++) {
            clock.advanceMinutes(30);
            e.tick("w1", 10);
        }
        Run dead = store.run(T, Run.idFor(T, other.key(), "triage")).orElseThrow();
        assertEquals(RunStatus.DEAD, dead.status());
        assertEquals(4, dead.attempts());
    }

    @Test
    void aLargeInvoiceWaitsForAPersonWithoutHoldingAWorker() {
        tickets.tickets.put("T1", new Ticket("T1", "c1", "Server", "x", clock.instant(), "CLOSED", Category.PROJECT,
                null, "tech1", null));
        tickets.entries.put("e1", new TimeEntry("e1", "T1", "c1", "tech1", clock.instant(), 50 * 60, WorkKind.ONSITE,
                EntrySource.CHECKIN, EntryStatus.CONFIRMED, "te:checkin:1"));
        Engine e = engine(Faults.NONE);
        Event closed = new Event(T, "erp", "m-2026-10-c1", EventTypes.MONTH_CLOSED, clock.instant(),
                Map.of("customerId", "c1", "month", "2026-10"));
        e.handle(closed, "w1");
        Run r = store.runs(T, RuntimeStore.RunQuery.latest(1)).get(0);
        assertEquals(RunStatus.WAITING_APPROVAL, r.status());
        assertEquals(0, systems.invoices.size());
        Approval a = store.pendingApprovals(T).get(0);
        e.decide(T, a.id(), true, "office@acme-it.de");
        e.tick("w1", 10);
        assertEquals(1, systems.invoices.size());
        assertEquals(6000_00 + 45_00, systems.invoices.get(0).totalCents());
        assertEquals(RunStatus.DONE, store.run(T, r.id()).orElseThrow().status());
    }

    @Test
    void aRejectedInvoiceIsNotCreated() {
        tickets.tickets.put("T1", new Ticket("T1", "c1", "Server", "x", clock.instant(), "CLOSED", null, null,
                "tech1", null));
        tickets.entries.put("e1", new TimeEntry("e1", "T1", "c1", "tech1", clock.instant(), 20, WorkKind.REMOTE,
                EntrySource.RMM, EntryStatus.CONFIRMED, "te:rmm:1"));
        Engine e = engine(Faults.NONE);
        e.handle(new Event(T, "erp", "m1", EventTypes.MONTH_CLOSED, clock.instant(),
                Map.of("customerId", "c1", "month", "2026-10")), "w1");
        Approval a = store.pendingApprovals(T).get(0);
        assertTrue(a.reason().contains("without a category"));
        e.decide(T, a.id(), false, "office@acme-it.de");
        e.tick("w1", 10);
        assertEquals(0, systems.invoices.size());
        assertEquals(RunStatus.FAILED, store.runs(T, RuntimeStore.RunQuery.latest(1)).get(0).status());
    }

    @Test
    void aDryRunPlansWritesWithoutMakingThem() {
        Run r = Engine.dryRun(new mspagent.core.agent.TriageAgent(),
                new AgentVersion(T, "triage", 7, Map.of("minNeighbours", 5), AgentVersion.Status.DRAFT, "t",
                        clock.instant(), null),
                created(), Fakes.tools(tickets, systems, llm), clock, settings, new InMemoryRuntimeStore());
        assertEquals(RunStatus.DONE, r.status());
        assertEquals(0, tickets.writes);
    }

    @Test
    void anUnpublishedAgentGetsNoEvents() {
        RuntimeStore empty = new InMemoryRuntimeStore();
        Engine e = new Engine(empty, Router.fromAgents(Agents.builtIn()), Agents.builtIn(),
                Fakes.tools(tickets, systems, llm), clock, settings, Faults.NONE, RunListener.NONE);
        assertEquals(0, e.handle(created(), "w1").started());
    }

    @Test
    void tracesCarryTokensAndCostAndExportAsOtlp() {
        engine(Faults.NONE).handle(created(), "w1");
        Run r = store.runs(T, RuntimeStore.RunQuery.latest(1)).get(0);
        var spans = store.spans(T, r.id());
        var chat = spans.stream().filter(s -> s.name().equals("chat triage.read")).findFirst().orElseThrow();
        assertEquals("400", chat.attributes().get("gen_ai.usage.input_tokens"));
        // 400 * 40 + 80 * 200 cents per million tokens = 320 micro-euros.
        assertEquals("320", chat.attributes().get("msp.cost_micros"));
        String otlp = OtlpJson.traces("msp-agent-runtime", spans);
        assertTrue(otlp.contains("\"resourceSpans\""));
        assertTrue(otlp.contains("\"intValue\":\"400\""));
    }
}
