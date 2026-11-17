package mspagent.core.port;

import mspagent.core.domain.Category;
import mspagent.core.domain.Priority;

/** What triage writes back to the ticket. */
public record TriageDecision(
        String ticketId,
        Priority priority,
        Category category,
        String assignee,
        boolean needsReview,
        String reason,
        String externalRef) {}
