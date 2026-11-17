package mspagent.world;

import java.time.Instant;
import java.util.List;
import mspagent.core.domain.Category;
import mspagent.core.domain.Impact;
import mspagent.core.domain.Priority;
import mspagent.core.domain.Urgency;
import mspagent.core.domain.WorkKind;

/** What really happened in the simulated month. Only the world and the scoring see these. */
public final class Truth {
    private Truth() {}

    public record TrueTicket(
            String id,
            String customerId,
            String templateId,
            Category category,
            Category confusable,
            Impact impact,
            Urgency urgency,
            Priority priority,
            String subject,
            String body,
            List<String> evidence,
            String injection,
            boolean vipInjection,
            Instant createdAt,
            Instant closedAt,
            String worker) {

        public String text() {
            return subject + "\n" + body;
        }
    }

    public record TrueSession(
            String id,
            String ticketId,
            String technicianId,
            Instant start,
            Instant end,
            WorkKind kind,
            boolean viaRmm,
            boolean checkedIn) {

        public int minutes() {
            return (int) Math.max(1, (end.getEpochSecond() - start.getEpochSecond() + 59) / 60);
        }
    }

    /** One sentence of a note. {@code sessionId} is null for a distractor or a secret. */
    public record Segment(String text, String quote, String sessionId, int minutes, WorkKind kind, Integer startMinute,
            boolean unseenFormat, boolean distractor) {}

    public record TrueNote(String id, String ticketId, String technicianId, Instant writtenAt, String text,
            boolean internal, List<Segment> segments, List<String> secrets) {}

    /** A time entry a technician will type, if nothing covers the work yet when they get to it. */
    public record ManualPlan(String sessionId, String ticketId, String customerId, String technicianId, Instant start,
            int minutes, WorkKind kind, Instant enteredAt) {}

    public record StatusChange(String id, String ticketId, String status, Instant at) {}

    public record HistoryTicket(String templateId, Category category, Priority priority) {}
}
