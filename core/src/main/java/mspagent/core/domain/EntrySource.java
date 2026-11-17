package mspagent.core.domain;

/** Where a time entry came from, from the most to the least authoritative. */
public enum EntrySource {
    /** The RMM tool's remote-session log: start and end are measured. */
    RMM,
    /** The field app's on-site check-in and check-out. */
    CHECKIN,
    /** Typed by the technician. */
    MANUAL,
    /** Read from the technician's ticket notes by the time-capture agent. */
    NOTE
}
