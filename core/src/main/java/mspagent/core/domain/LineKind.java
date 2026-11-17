package mspagent.core.domain;

/** The kinds of invoice line the billing rules produce. */
public enum LineKind {
    FLAT_FEE,
    PREPAID_DRAW,
    LABOUR,
    TRAVEL
}
