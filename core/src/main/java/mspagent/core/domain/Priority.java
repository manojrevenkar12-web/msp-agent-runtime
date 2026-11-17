package mspagent.core.domain;

/** Ticket priority from the ITIL impact and urgency matrix. */
public enum Priority {
    P1,
    P2,
    P3,
    P4;

    private static final Priority[][] MATRIX = {
        // WORK_STOPPED, DEGRADED, CAN_WAIT
        {P1, P2, P3}, // COMPANY
        {P2, P3, P4}, // TEAM
        {P3, P4, P4}, // SINGLE_USER
    };

    public static Priority of(Impact impact, Urgency urgency) {
        return MATRIX[impact.ordinal()][urgency.ordinal()];
    }
}
