package mspagent.core.domain;

/** One line of a monthly invoice. Prepaid draws carry minutes and no amount. */
public record InvoiceLine(LineKind kind, String ticketId, int minutes, long amountCents, String text) {

    public InvoiceLine withText(String t) {
        return new InvoiceLine(kind, ticketId, minutes, amountCents, t);
    }
}
