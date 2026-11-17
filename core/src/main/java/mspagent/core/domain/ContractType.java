package mspagent.core.domain;

/** How a customer pays for work. */
public enum ContractType {
    /** A monthly fee covers incidents and security alerts; other work is charged by the hour. */
    MANAGED_FLAT,
    /** A monthly pool of prepaid minutes is drawn first; work beyond it is charged by the hour. */
    PREPAID_HOURS,
    /** Every minute is charged by the hour. */
    TIME_AND_MATERIAL
}
