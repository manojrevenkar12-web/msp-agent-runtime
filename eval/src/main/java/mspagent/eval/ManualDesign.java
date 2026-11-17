package mspagent.eval;

import java.util.ArrayList;
import java.util.List;
import mspagent.core.domain.BillableEntry;
import mspagent.core.domain.BillingRules;
import mspagent.core.domain.Category;
import mspagent.core.domain.Contract;
import mspagent.core.domain.Invoice;
import mspagent.core.domain.TimeEntry;
import mspagent.core.event.Event;
import mspagent.core.event.EventTypes;
import mspagent.core.port.Ticket;
import mspagent.world.Truth.TrueTicket;
import mspagent.world.World;

/**
 * The status quo: a dispatcher triages by hand in office hours, technicians type their time, and the office bills
 * from what was typed with the same billing rules. No agents.
 */
final class ManualDesign implements Timeline.Consumer {
    private final World w;

    ManualDesign(World w) {
        this.w = w;
    }

    @Override
    public void deliver(Event e, Timeline t) {
        if (!e.type().equals(EventTypes.TICKET_CREATED) || t.sys.triage.containsKey(e.get("ticketId"))) {
            return;
        }
        TrueTicket tt = w.ticketById.get(e.get("ticketId"));
        java.time.Instant at = t.sys.dispatcherTime(tt.createdAt());
        t.at(at, () -> {
            if (!t.sys.triage.containsKey(tt.id())) {
                t.sys.dispatch(tt, at);
            }
        });
    }

    @Override
    public void monthClosed(Timeline t) {
        for (Contract c : w.contracts.values()) {
            List<BillableEntry> b = new ArrayList<>();
            for (TimeEntry e : t.sys.confirmedEntries(w.tenant, c.customerId(), w.month)) {
                Ticket tk = t.sys.ticket(w.tenant, e.ticketId());
                b.add(new BillableEntry(e.ticketId(), e.technicianId(), e.start(), e.minutes(), e.kind(),
                        tk.category() == null ? Category.SERVICE_REQUEST : tk.category()));
            }
            t.sys.createInvoice(w.tenant, new Invoice(c.customerId(), w.month, BillingRules.lines(c, b),
                    "invoice:" + c.customerId() + ":" + w.month));
        }
    }
}
