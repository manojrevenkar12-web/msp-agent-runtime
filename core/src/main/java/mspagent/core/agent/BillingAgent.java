package mspagent.core.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mspagent.core.domain.BillableEntry;
import mspagent.core.domain.BillingRules;
import mspagent.core.domain.Contract;
import mspagent.core.domain.EntrySource;
import mspagent.core.domain.Invoice;
import mspagent.core.domain.InvoiceLine;
import mspagent.core.domain.LineKind;
import mspagent.core.domain.TimeEntry;
import mspagent.core.engine.Ctx;
import mspagent.core.event.EventTypes;
import mspagent.core.port.Ticket;

/**
 * Builds a customer's monthly invoice from confirmed time entries and the contract, and creates it in the ERP once.
 * No model is involved: the billing rules are code ({@link BillingRules}). Invoices above a limit, or with tickets
 * nobody categorised, wait for a person.
 */
public final class BillingAgent implements Agent {

    @Override
    public String id() {
        return "billing";
    }

    @Override
    public String title() {
        return "Monthly billing";
    }

    @Override
    public String description() {
        return "Turns confirmed time entries into the monthly invoice by the contract's rules; large invoices wait for "
                + "approval.";
    }

    @Override
    public List<String> eventTypes() {
        return List.of(EventTypes.MONTH_CLOSED);
    }

    @Override
    public List<String> testedFields() {
        return List.of("needsApproval", "uncategorised");
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.cents("approvalAboveCents", 500_000, 0, 10_000_000,
                        "Invoices above this total wait for a person (cents)"),
                ParamSpec.flag("approveNoteEntries", false, "Invoices with time read from notes wait for a person"));
    }

    public record Inputs(Contract contract, List<TimeEntry> entries, Map<String, Ticket> tickets) {}

    public record Decision(Invoice invoice, int uncategorised, int fromNotes, boolean needsApproval, String reason) {}

    @Override
    public void run(Ctx ctx) {
        String customerId = ctx.event().get("customerId");
        String month = ctx.event().get("month");
        Inputs in = ctx.step("inputs", Inputs.class, () -> {
            Contract c = ctx.tools().erp().contract(ctx.tenant(), customerId);
            List<TimeEntry> entries = ctx.tools().tickets().confirmedEntries(ctx.tenant(), customerId, month);
            Map<String, Ticket> tickets = new HashMap<>();
            for (TimeEntry e : entries) {
                tickets.computeIfAbsent(e.ticketId(), id -> ctx.tools().tickets().ticket(ctx.tenant(), id));
            }
            return new Inputs(c, entries, tickets);
        });
        Decision d = ctx.step("decision", Decision.class, () -> decide(ctx.config(), in, month));
        if (d.needsApproval()) {
            ctx.approval("invoice", d.reason(), d.invoice());
        }
        Invoice inv = d.invoice();
        ctx.effect(inv.externalRef(), "erp.createInvoice", () -> ctx.tools().erp().exists(ctx.tenant(), inv.externalRef()),
                () -> ctx.tools().erp().createInvoice(ctx.tenant(), inv));
    }

    static Decision decide(AgentConfig cfg, Inputs in, String month) {
        List<BillableEntry> billable = new ArrayList<>();
        int uncategorised = 0;
        int fromNotes = 0;
        for (TimeEntry e : in.entries()) {
            Ticket t = in.tickets().get(e.ticketId());
            if (t.category() == null) {
                uncategorised++;
            }
            if (e.source() == EntrySource.NOTE) {
                fromNotes++;
            }
            billable.add(new BillableEntry(e.ticketId(), e.technicianId(), e.start(), e.minutes(), e.kind(),
                    t.category() == null ? mspagent.core.domain.Category.SERVICE_REQUEST : t.category()));
        }
        List<InvoiceLine> lines = new ArrayList<>();
        for (InvoiceLine l : BillingRules.lines(in.contract(), billable)) {
            if (l.text() == null) {
                Ticket t = in.tickets().get(l.ticketId());
                String what = l.kind() == LineKind.PREPAID_DRAW ? "From prepaid hours: " : "";
                l = l.withText(what + t.id() + " " + t.subject());
            }
            lines.add(l);
        }
        Invoice inv = new Invoice(in.contract().customerId(), month, lines,
                "invoice:" + in.contract().customerId() + ":" + month);
        List<String> why = new ArrayList<>();
        if (inv.totalCents() > cfg.get("approvalAboveCents")) {
            why.add("total above the approval limit");
        }
        if (uncategorised > 0) {
            why.add(uncategorised + " entries on tickets without a category, billed as service requests");
        }
        if (fromNotes > 0 && cfg.flag("approveNoteEntries")) {
            why.add(fromNotes + " entries read from notes");
        }
        return new Decision(inv, uncategorised, fromNotes, !why.isEmpty(), String.join("; ", why));
    }
}
