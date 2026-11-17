package mspagent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mspagent.core.agent.Agents;
import mspagent.core.domain.Category;
import mspagent.core.engine.AgentVersion;
import mspagent.core.engine.Engine;
import mspagent.core.engine.EngineSettings;
import mspagent.core.engine.Faults;
import mspagent.core.engine.InMemoryRuntimeStore;
import mspagent.core.engine.RunListener;
import mspagent.core.event.Event;
import mspagent.core.event.EventTypes;
import mspagent.core.port.Note;
import mspagent.core.port.Technician;
import mspagent.core.port.Ticket;
import mspagent.core.route.Router;
import org.junit.jupiter.api.Test;

class ConciergeTest {
    final Fakes.MutableClock clock = new Fakes.MutableClock(Instant.parse("2026-10-05T08:00:00Z"));
    final Fakes.Tickets tickets = new Fakes.Tickets();
    final Fakes.Systems systems = new Fakes.Systems();
    final Fakes.ScriptedLlm llm = new Fakes.ScriptedLlm();
    final InMemoryRuntimeStore store = new InMemoryRuntimeStore();

    Engine engine() {
        store.saveVersion(new AgentVersion("t", "concierge", 1, Map.of(), AgentVersion.Status.PUBLISHED, "x",
                clock.instant(), null));
        tickets.tickets.put("T1", new Ticket("T1", "c1", "VPN", "VPN geht nicht", clock.instant(), "ASSIGNED",
                Category.INCIDENT, null, "b", null));
        tickets.techs.add(new Technician("b", "Ben", Set.of(Category.INCIDENT), 0));
        tickets.notes.put("T1", List.of(new Note("n1", "T1", "b", clock.instant(),
                "Firewall-Regel 17 auf der Sophos beim Kunden war falsch gesetzt, Admin-Passwort ist Sommer2026", true)));
        return new Engine(store, Router.fromAgents(Agents.builtIn()), Agents.builtIn(),
                Fakes.tools(tickets, systems, llm), clock,
                new EngineSettings(3, Duration.ofMinutes(1), Duration.ofMinutes(5), Map.of()), Faults.NONE,
                RunListener.NONE);
    }

    Event status(String id, String s) {
        return new Event("t", "tickets", id, EventTypes.TICKET_STATUS, clock.instant(),
                Map.of("ticketId", "T1", "status", s));
    }

    @Test
    void aDraftThatRepeatsAnInternalNoteIsReplacedByTheTemplate() {
        llm.update = r -> "Gute Nachricht: die Firewall-Regel 17 auf der Sophos beim Kunden war falsch gesetzt.";
        engine().handle(status("s1", "RESOLVED"), "w");
        assertEquals(1, tickets.updates.size());
        assertTrue(tickets.updates.get(0).startsWith("Ihr Ticket \"VPN\" ist gelöst"));
    }

    @Test
    void oneUpdatePerTicketAndStatusEvenWhenTheStatusIsSetTwice() {
        Engine e = engine();
        e.handle(status("s1", "ASSIGNED"), "w");
        e.handle(status("s2", "ASSIGNED"), "w");
        assertEquals(1, tickets.updates.size());
        assertEquals("Update: VPN", tickets.updates.get(0));
    }
}
