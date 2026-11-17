package mspagent.core.port;

import mspagent.core.domain.Category;
import mspagent.core.domain.Priority;

/** A resolved ticket similar to a new one, with the category and priority it ended with. */
public record Neighbour(String ticketId, double similarity, Category category, Priority priority) {}
