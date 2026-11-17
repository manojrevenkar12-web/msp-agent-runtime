package mspagent.core.port;

import java.time.Instant;

/** An on-site visit recorded by the field app (check-in to check-out). */
public record CheckIn(String id, String ticketId, String technicianId, Instant start, Instant end) {}
