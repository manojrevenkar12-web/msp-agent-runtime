package mspagent.core.domain;

/** Only confirmed entries are billed. */
public enum EntryStatus {
    CONFIRMED,
    PENDING,
    VOID
}
