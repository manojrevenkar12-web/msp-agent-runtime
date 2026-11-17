package mspagent.core.event;

/** The event types the runtime routes. Names follow the source systems' webhook names. */
public final class EventTypes {
    public static final String TICKET_CREATED = "ticket.created";
    public static final String TICKET_STATUS = "ticket.status_changed";
    public static final String NOTE_ADDED = "ticket.note_added";
    public static final String SESSION_ENDED = "rmm.session_ended";
    public static final String CHECKED_OUT = "field.checked_out";
    public static final String MONTH_CLOSED = "erp.month_closed";
    public static final String APPROVAL_DECIDED = "runtime.approval_decided";

    private EventTypes() {}
}
