package mspagent.core.port;

import java.util.List;

/** What a model gets to draft a customer update. Internal notes are passed so it can summarise progress. */
public record UpdateRequest(String ticketId, String subject, String status, String technicianName, List<Note> notes) {}
