package mspagent.core.domain;

import java.time.Instant;

/** A confirmed time entry together with the category of its ticket, which is all the billing rules need. */
public record BillableEntry(
        String ticketId, String technicianId, Instant start, int minutes, WorkKind kind, Category category) {}
