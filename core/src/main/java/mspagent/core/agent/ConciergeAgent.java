package mspagent.core.agent;

import java.util.List;
import mspagent.core.engine.Ctx;
import mspagent.core.event.EventTypes;
import mspagent.core.port.Note;
import mspagent.core.port.Technician;
import mspagent.core.port.Ticket;
import mspagent.core.port.UpdateRequest;

/**
 * Tells the customer when their ticket was assigned, needs them, or was resolved, so they do not have to ask. A model
 * drafts the message; code sends it once per ticket and status, and only if it repeats nothing from an internal note
 * and contains nothing that looks like a credential or an address. Otherwise a fixed template goes out.
 */
public final class ConciergeAgent implements Agent {

    @Override
    public String id() {
        return "concierge";
    }

    @Override
    public String title() {
        return "Customer updates";
    }

    @Override
    public String description() {
        return "Sends the customer one update per status change; drafts that leak internal notes are replaced.";
    }

    @Override
    public List<String> eventTypes() {
        return List.of(EventTypes.TICKET_STATUS);
    }

    @Override
    public List<String> testedFields() {
        return List.of("send", "template");
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.flag("onAssigned", true, "Update when a technician takes the ticket"),
                ParamSpec.flag("onWaiting", true, "Update when the ticket waits for the customer"),
                ParamSpec.flag("onResolved", true, "Update when the ticket is resolved"),
                ParamSpec.integer("repeatWords", 6, 3, 20, "Words in a row from an internal note that block a draft"),
                ParamSpec.integer("maxLength", 700, 100, 4000, "Longest message sent"));
    }

    public record Facts(Ticket ticket, List<Note> notes, String technicianName) {}

    public record Decision(boolean send, String text, boolean template, String reason) {}

    @Override
    public void run(Ctx ctx) {
        String ticketId = ctx.event().get("ticketId");
        String status = ctx.event().get("status");
        if (!wanted(ctx.config(), status)) {
            ctx.step("decision", Decision.class, () -> new Decision(false, null, false, "updates for " + status + " are off"));
            return;
        }
        Facts f = ctx.step("facts", Facts.class, () -> {
            Ticket t = ctx.tools().tickets().ticket(ctx.tenant(), ticketId);
            String name = ctx.tools().tickets().technicians(ctx.tenant()).stream()
                    .filter(x -> x.id().equals(t.assignee()))
                    .map(Technician::name)
                    .findFirst()
                    .orElse("our team");
            return new Facts(t, ctx.tools().tickets().notes(ctx.tenant(), ticketId), name);
        });
        String draft = ctx.step("draft", String.class, () -> ctx.llm("concierge.draft", () -> ctx.tools().llm()
                .draftUpdate(new UpdateRequest(ticketId, f.ticket().subject(), status, f.technicianName(), f.notes())))
                .value());
        Decision d = ctx.step("decision", Decision.class, () -> check(ctx.config(), f, status, draft));
        String ref = "update:" + ticketId + ":" + status;
        ctx.effect(ref, "tickets.postUpdate", () -> ctx.tools().tickets().exists(ctx.tenant(), ref),
                () -> ctx.tools().tickets().postUpdate(ctx.tenant(), ticketId, d.text(), ref));
    }

    static boolean wanted(AgentConfig cfg, String status) {
        return switch (status) {
            case "ASSIGNED" -> cfg.flag("onAssigned");
            case "WAITING_ON_CUSTOMER" -> cfg.flag("onWaiting");
            case "RESOLVED" -> cfg.flag("onResolved");
            default -> false;
        };
    }

    static Decision check(AgentConfig cfg, Facts f, String status, String draft) {
        List<String> internal = f.notes().stream().filter(Note::internal).map(Note::text).toList();
        String reason = null;
        if (draft == null || draft.isBlank()) {
            reason = "empty draft";
        } else if (draft.length() > cfg.integer("maxLength")) {
            reason = "draft too long";
        } else if (Text.containsSecret(draft)) {
            reason = "draft contains something like a credential or an IP address";
        } else if (Text.repeats(draft, internal, cfg.integer("repeatWords"))) {
            reason = "draft repeats an internal note";
        }
        if (reason == null) {
            return new Decision(true, draft, false, "draft passed the checks");
        }
        return new Decision(true, template(f, status), true, reason);
    }

    static String template(Facts f, String status) {
        String s = f.ticket().subject();
        return switch (status) {
            case "ASSIGNED" -> "Ihr Ticket \"" + s + "\" wird jetzt von " + f.technicianName() + " bearbeitet.";
            case "WAITING_ON_CUSTOMER" -> "Zu Ihrem Ticket \"" + s + "\" brauchen wir eine Rückmeldung von Ihnen.";
            case "RESOLVED" -> "Ihr Ticket \"" + s + "\" ist gelöst. Melden Sie sich gern, falls noch etwas fehlt.";
            default -> "Ihr Ticket \"" + s + "\" wurde aktualisiert.";
        };
    }
}
