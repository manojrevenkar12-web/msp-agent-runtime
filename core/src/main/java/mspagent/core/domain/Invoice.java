package mspagent.core.domain;

import java.util.List;

/** A customer's invoice for one month. */
public record Invoice(String customerId, String month, List<InvoiceLine> lines, String externalRef) {

    public long totalCents() {
        return lines.stream().mapToLong(InvoiceLine::amountCents).sum();
    }
}
