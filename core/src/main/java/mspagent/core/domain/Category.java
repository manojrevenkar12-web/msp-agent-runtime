package mspagent.core.domain;

/** What kind of work a ticket is. Decides whether a managed-service flat fee covers it. */
public enum Category {
    INCIDENT(true),
    SECURITY_ALERT(true),
    SERVICE_REQUEST(false),
    INSTALLATION(false),
    PROJECT(false);

    private final boolean coveredByFlat;

    Category(boolean coveredByFlat) {
        this.coveredByFlat = coveredByFlat;
    }

    /** True when a managed-service flat fee includes this work, so it is not charged by the hour. */
    public boolean coveredByFlat() {
        return coveredByFlat;
    }
}
