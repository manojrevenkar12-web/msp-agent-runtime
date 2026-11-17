package mspagent.core.port;

import java.time.Instant;

/** A technician's note on a ticket. Internal notes must never reach the customer. */
public record Note(String id, String ticketId, String technicianId, Instant writtenAt, String text, boolean internal) {}
