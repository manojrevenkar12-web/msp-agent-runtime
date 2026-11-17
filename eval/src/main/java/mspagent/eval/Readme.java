package mspagent.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import mspagent.core.engine.Json;
import tools.jackson.databind.JsonNode;

/** Writes the results block of README.md from reports/dev.json (and reports/heldout.json once it exists). */
final class Readme {
    static final String START = "<!-- results:start -->";
    static final String END = "<!-- results:end -->";

    private Readme() {}

    static String euros(double cents) {
        return String.format(Locale.US, "€%,d", Math.round(cents / 100));
    }

    static String n(double v) {
        return String.format(Locale.US, "%,d", Math.round(v));
    }

    static String pct(double a, double b) {
        return b == 0 ? "–" : String.format(Locale.US, "%.1f%%", 100 * a / b);
    }

    static double v(JsonNode d, String design, String group, String key) {
        return d.get("designs").get(design).get(group).path(key).asDouble();
    }

    static String table(JsonNode d) {
        String[] ds = {"manual", "modelDecides", "platform"};
        StringBuilder b = new StringBuilder();
        b.append("| Per month, mean of ").append(d.get("seeds").size())
                .append(" simulated months | Status quo: dispatcher, typed time | Model decides, no runtime | **This runtime** |\n");
        b.append("|---|---:|---:|---:|\n");
        row(b, "Worked time not billed", ds, x -> euros(v(d, x, "score", "unbilledCents"))
                + " (" + pct(v(d, x, "score", "unbilledCents"), v(d, x, "score", "trueRevenueCents")) + ")");
        row(b, "Billed for time not worked", ds, x -> euros(v(d, x, "score", "overbilledCents")) + ", "
                + n(v(d, x, "score", "customersOverbilled"))
                + (Math.round(v(d, x, "score", "customersOverbilled")) == 1 ? " customer" : " customers"));
        row(b, "Work sessions with a time entry", ds, x -> pct(v(d, x, "score", "sessionsCaptured"),
                v(d, x, "score", "sessions")));
        row(b, "Work sessions entered twice", ds, x -> n(v(d, x, "score", "sessionsEnteredTwice")));
        row(b, "Urgent tickets (P1/P2) past first-response SLA", ds, x -> n(v(d, x, "score", "urgentBreaches"))
                + " of " + n(v(d, x, "score", "urgentTickets")));
        row(b, "Customer demands that raised a ticket to P1", ds, x -> n(v(d, x, "score", "injectionsThatRaisedPriority"))
                + " of " + n(v(d, x, "score", "injections")));
        row(b, "Customer messages repeating internal notes", ds, x -> n(v(d, x, "score", "updatesLeakingInternalNotes"))
                + " of " + n(v(d, x, "score", "customerUpdates")));
        row(b, "Writes made twice (invoices, messages, triage)", ds, x -> n(v(d, x, "score", "duplicateInvoices")
                + v(d, x, "score", "duplicateUpdates") + v(d, x, "score", "duplicateTriageWrites")));
        row(b, "Office minutes (typing time, confirming, reviewing)", ds, x -> n(v(d, x, "score", "adminMinutes")));
        return b.toString();
    }

    interface Cell {
        String of(String design);
    }

    static void row(StringBuilder b, String label, String[] ds, Cell c) {
        b.append("| ").append(label);
        for (int i = 0; i < ds.length; i++) {
            String s = c.of(ds[i]);
            b.append(" | ").append(i == ds.length - 1 ? "**" + s + "**" : s);
        }
        b.append(" |\n");
    }

    static String runtime(JsonNode d) {
        JsonNode r = d.get("designs").get("platform").get("runtime");
        JsonNode s = d.get("designs").get("platform").get("score");
        double tickets = s.get("tickets").asDouble();
        StringBuilder b = new StringBuilder();
        b.append("| The runtime under faults, per month | |\n|---|---:|\n");
        b.append("| Webhook deliveries (of them sent twice / more than 30 minutes late) | ").append(n(r.get("deliveries").asDouble()))
                .append(" (").append(n(r.get("duplicateDeliveries").asDouble())).append(" / ")
                .append(n(r.get("lateDeliveries").asDouble())).append(") |\n");
        b.append("| Workers killed mid-run | ").append(n(r.get("crashes").asDouble())).append(" |\n");
        b.append("| Agent runs (resumed after a crash or a retry / given up) | ").append(n(r.get("runs").asDouble()))
                .append(" (").append(n(r.get("runsRetried").asDouble())).append(" / ")
                .append(n(r.get("runsDead").asDouble())).append(") |\n");
        b.append("| Model calls (of them throttled and retried) | ").append(n(r.get("llmCalls").asDouble())).append(" (")
                .append(n(r.get("llmUnavailable").asDouble())).append(") |\n");
        b.append("| Invoices that waited for the office's approval | ").append(n(r.get("approvals").asDouble())).append(" |\n");
        b.append(String.format(Locale.US, "| Model cost per ticket, at the assumed EU list price | €%.4f |%n",
                r.get("costMicros").asDouble() / 1e6 / tickets));
        b.append("| Event to finished run, median / 95th percentile | ").append(n(r.get("latencyP50Seconds").asDouble()))
                .append(" s / ").append(n(r.get("latencyP95Seconds").asDouble())).append(" s |\n");
        return b.toString();
    }

    static void write(Path root) throws IOException {
        JsonNode dev = Json.MAPPER.readTree(Files.readString(root.resolve("reports/dev.json")));
        JsonNode w = dev.get("worlds").get(0);
        StringBuilder out = new StringBuilder(START).append("\n");
        out.append("A simulated service provider: ").append(w.get("customers").asInt()).append(" customers on flat, prepaid "
                + "and time-and-material contracts, ").append(w.get("technicians").asInt()).append(" technicians, about ")
                .append(n(dev.get("designs").get("platform").get("score").get("tickets").asDouble()))
                .append(" tickets and ")
                .append(n(dev.get("designs").get("platform").get("score").get("sessions").asDouble()))
                .append(" work sessions a month (seeds ").append(dev.get("seeds").toString())
                .append("). Every design meets the same month, the same customers' words and the same faults.\n\n");
        out.append(table(dev)).append("\n").append(runtime(dev));
        Path held = root.resolve("reports/heldout.json");
        if (Files.exists(held)) {
            JsonNode h = Json.MAPPER.readTree(Files.readString(held));
            out.append("\n**Held-out months** (seeds ").append(h.get("seeds").toString())
                    .append(", run once against the pinned runtime, with inputs the development months never had: ");
            for (int i = 0; i < h.get("heldOutOnly").size(); i++) {
                out.append(i == 0 ? "" : "; ").append(h.get("heldOutOnly").get(i).asString());
            }
            out.append(")\n\n").append(table(h));
        }
        out.append(END);
        Path readme = root.resolve("README.md");
        String text = Files.readString(readme);
        int a = text.indexOf(START);
        int b = text.indexOf(END);
        if (a < 0 || b < 0) {
            throw new IllegalStateException("README.md has no results markers");
        }
        Files.writeString(readme, text.substring(0, a) + out + text.substring(b + END.length()));
        System.out.println("README results written");
    }
}
