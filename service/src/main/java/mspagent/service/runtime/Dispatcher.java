package mspagent.service.runtime;

import mspagent.core.engine.Engine;
import mspagent.core.event.Event;
import mspagent.core.event.EventTypes;
import mspagent.core.port.Tools;
import mspagent.service.Settings;
import mspagent.service.similar.TicketHistory;
import org.springframework.stereotype.Component;

/** What a consumer does with one event: hand it to the engine, and keep resolved tickets for similarity search. */
@Component
public class Dispatcher {
    private final Engine engine;
    private final TicketHistory history;
    private final Tools tools;
    private final Settings settings;

    public Dispatcher(Engine engine, TicketHistory history, Tools tools, Settings settings) {
        this.engine = engine;
        this.history = history;
        this.tools = tools;
        this.settings = settings;
    }

    public Engine.Outcome dispatch(Event e) {
        Engine.Outcome o = engine.handle(e, settings.workerId());
        if (EventTypes.TICKET_STATUS.equals(e.type()) && "RESOLVED".equals(e.data().get("status"))) {
            history.index(e.tenant(), tools.tickets().ticket(e.tenant(), e.get("ticketId")));
        }
        return o;
    }
}
