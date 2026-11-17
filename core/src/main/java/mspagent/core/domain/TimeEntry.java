package mspagent.core.domain;

import java.time.Instant;

/** Work time on a ticket, as the ticket system stores it. */
public record TimeEntry(
        String id,
        String ticketId,
        String customerId,
        String technicianId,
        Instant start,
        int minutes,
        WorkKind kind,
        EntrySource source,
        EntryStatus status,
        String externalRef) {

    public Instant end() {
        return start.plusSeconds(60L * minutes);
    }

    public TimeEntry withStatus(EntryStatus s) {
        return new TimeEntry(id, ticketId, customerId, technicianId, start, minutes, kind, source, s, externalRef);
    }

    /** Minutes this entry shares with another. */
    public long overlapMinutes(Instant otherStart, Instant otherEnd) {
        long from = Math.max(start.getEpochSecond(), otherStart.getEpochSecond());
        long to = Math.min(end().getEpochSecond(), otherEnd.getEpochSecond());
        return Math.max(0, (to - from) / 60);
    }
}
