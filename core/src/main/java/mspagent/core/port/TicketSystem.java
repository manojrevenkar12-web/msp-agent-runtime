package mspagent.core.port;

import java.util.List;
import mspagent.core.domain.TimeEntry;

/**
 * The ticket system (ITSM) as the agents see it. Writes carry an external reference so that a write repeated after a
 * crash can be recognised ({@link #exists}).
 */
public interface TicketSystem {

    Ticket ticket(String tenant, String ticketId);

    List<Note> notes(String tenant, String ticketId);

    List<TimeEntry> entries(String tenant, String ticketId);

    /** Confirmed entries of a customer's tickets in a month ("2026-10"), with any source. */
    List<TimeEntry> confirmedEntries(String tenant, String customerId, String month);

    List<Technician> technicians(String tenant);

    void applyTriage(String tenant, TriageDecision decision);

    void addEntry(String tenant, TimeEntry entry);

    void voidEntry(String tenant, String entryId, String externalRef);

    void postUpdate(String tenant, String ticketId, String text, String externalRef);

    boolean exists(String tenant, String externalRef);
}
