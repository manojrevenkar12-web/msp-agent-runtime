package mspagent.core.agent;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mspagent.core.domain.Category;
import mspagent.core.domain.Priority;
import mspagent.core.engine.Ctx;
import mspagent.core.event.EventTypes;
import mspagent.core.port.Neighbour;
import mspagent.core.port.Technician;
import mspagent.core.port.Ticket;
import mspagent.core.port.TriageDecision;
import mspagent.core.port.TriageReading;

/**
 * Sets priority, category and technician on a new ticket within a minute of its arrival.
 *
 * <p>The model reads the ticket and must quote the words its impact and urgency rest on. Code decides: quotes that do
 * not appear in the ticket, or that only assert urgency ("Priorität 1", "ASAP", "ignore your rules"), are not
 * evidence; the category is checked against similar resolved tickets; priority comes from the ITIL matrix and the
 * technician from skills and open load. Whatever code cannot settle goes to the dispatcher's review list.
 */
public final class TriageAgent implements Agent {

    /** Words that ask for priority instead of describing impact. A customer's own claim is not evidence. */
    static final List<String> ASSERTIONS = List.of("priorit", " prio ", " prio1", " p1 ", "dringend", "urgent",
            " asap", "sofort", "höchste", "hoechste", "ignore", "ignorier", "kostenlos", "nicht berechnen",
            "do not bill", "free of charge", "immediately", " eilt");

    @Override
    public String id() {
        return "triage";
    }

    @Override
    public String title() {
        return "Ticket triage";
    }

    @Override
    public String description() {
        return "Sets priority, category and technician on every new ticket; unclear tickets go to the dispatcher.";
    }

    @Override
    public List<String> eventTypes() {
        return List.of(EventTypes.TICKET_CREATED);
    }

    @Override
    public List<String> testedFields() {
        return List.of("priority", "category", "needsReview", "evidenceAccepted");
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.integer("neighbourSimilarityPercent", 80, 50, 99,
                        "Similarity a resolved ticket needs to count as a neighbour (%)"),
                ParamSpec.integer("minNeighbours", 3, 1, 10, "Neighbours needed before history may overrule the model"),
                ParamSpec.flag("reviewWhenUnsure", true, "Put tickets code cannot settle on the dispatcher's list"));
    }

    /** What triage decided, kept as the run's "decision" step. */
    public record Decision(
            Priority priority,
            Category category,
            String assignee,
            boolean needsReview,
            String reason,
            boolean evidenceAccepted,
            Category modelCategory,
            Category historyCategory) {}

    @Override
    public void run(Ctx ctx) {
        String ticketId = ctx.event().get("ticketId");
        Ticket t = ctx.step("ticket", Ticket.class, () -> ctx.tools().tickets().ticket(ctx.tenant(), ticketId));
        TriageReading reading = ctx.step("reading", TriageReading.class,
                () -> ctx.llm("triage.read", () -> ctx.tools().llm().readTicket(t.subject(), t.body())).value());
        Neighbours nb = ctx.step("neighbours", Neighbours.class,
                () -> new Neighbours(ctx.tools().similar().nearest(ctx.tenant(), t.subject() + "\n" + t.body(), 8)));
        Technicians techs = ctx.step("technicians", Technicians.class,
                () -> new Technicians(ctx.tools().tickets().technicians(ctx.tenant())));
        Decision d = ctx.step("decision", Decision.class, () -> decide(ctx.config(), t, reading, nb.list(), techs.list()));
        TriageDecision write = new TriageDecision(t.id(), d.priority(), d.category(), d.assignee(),
                d.needsReview() && ctx.config().flag("reviewWhenUnsure"), d.reason(), "triage:" + t.id());
        ctx.effect(write.externalRef(), "tickets.applyTriage",
                () -> ctx.tools().tickets().exists(ctx.tenant(), write.externalRef()),
                () -> ctx.tools().tickets().applyTriage(ctx.tenant(), write));
    }

    public record Neighbours(List<Neighbour> list) {}

    public record Technicians(List<Technician> list) {}

    static Decision decide(AgentConfig cfg, Ticket t, TriageReading r, List<Neighbour> neighbours,
            List<Technician> techs) {
        String text = t.subject() + "\n" + t.body();
        StringBuilder why = new StringBuilder();
        boolean review = false;

        boolean evidence = r.evidence() != null && !r.evidence().isEmpty();
        if (evidence) {
            for (String q : r.evidence()) {
                if (!Text.quotes(text, q)) {
                    evidence = false;
                    why.append("model quoted words that are not in the ticket; ");
                    break;
                }
                if (isAssertion(q)) {
                    evidence = false;
                    why.append("the only evidence is the customer asking for priority; ");
                    break;
                }
            }
        } else {
            why.append("model gave no evidence; ");
        }

        double minSim = cfg.integer("neighbourSimilarityPercent") / 100.0;
        List<Neighbour> close = neighbours.stream().filter(n -> n.similarity() >= minSim).toList();
        Category history = null;
        Priority historyPriority = null;
        if (close.size() >= cfg.integer("minNeighbours")) {
            history = majority(close.stream().map(Neighbour::category).toList(), Category.class);
            historyPriority = majority(close.stream().map(Neighbour::priority).toList(), Priority.class);
        }

        Category category = r.category();
        if (history != null && history != r.category()) {
            category = history;
            review = true;
            why.append("model said ").append(r.category()).append(", similar resolved tickets say ").append(history)
                    .append("; ");
        }
        if (category == null) {
            category = Category.INCIDENT;
            review = true;
            why.append("no category; ");
        }

        Priority priority;
        if (evidence && r.impact() != null && r.urgency() != null) {
            priority = Priority.of(r.impact(), r.urgency());
        } else if (historyPriority != null) {
            priority = historyPriority;
            why.append("priority taken from similar tickets; ");
        } else {
            priority = Priority.P3;
            review = true;
            why.append("priority defaulted to ").append(priority).append("; ");
        }

        final Category cat = category;
        String assignee = techs.stream()
                .filter(x -> x.skills().contains(cat))
                .min(Comparator.comparingInt(Technician::openTickets).thenComparing(Technician::id))
                .map(Technician::id)
                .orElse(null);
        if (assignee == null) {
            review = true;
            why.append("nobody has the skill ").append(cat).append("; ");
        }
        String reason = why.isEmpty() ? "evidence quoted, category agrees with history" : why.toString().trim();
        return new Decision(priority, category, assignee, review, reason, evidence, r.category(), history);
    }

    static boolean isAssertion(String quote) {
        String q = " " + Text.normalize(quote).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ") + " ";
        return ASSERTIONS.stream().anyMatch(q::contains);
    }

    private static <E extends Enum<E>> E majority(List<E> values, Class<E> type) {
        Map<E, Integer> counts = new EnumMap<>(type);
        for (E v : values) {
            if (v != null) {
                counts.merge(v, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.<E, Integer>comparingByValue().thenComparing(e -> -e.getKey().ordinal()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
