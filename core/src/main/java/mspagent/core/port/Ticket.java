package mspagent.core.port;

import java.time.Instant;
import mspagent.core.domain.Category;
import mspagent.core.domain.Priority;

/** A ticket as the ticket system returns it. Category, priority and assignee are empty until triage sets them. */
public record Ticket(
        String id,
        String customerId,
        String subject,
        String body,
        Instant createdAt,
        String status,
        Category category,
        Priority priority,
        String assignee,
        Instant closedAt) {}
