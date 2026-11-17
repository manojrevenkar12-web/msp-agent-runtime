package mspagent.core.port;

import java.time.Instant;

/** A remote-control session measured by the RMM tool. */
public record RemoteSession(String id, String ticketId, String technicianId, Instant start, Instant end) {}
