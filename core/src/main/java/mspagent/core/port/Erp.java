package mspagent.core.port;

import mspagent.core.domain.Contract;
import mspagent.core.domain.Invoice;

/** The ERP: contracts and invoices. */
public interface Erp {

    Contract contract(String tenant, String customerId);

    void createInvoice(String tenant, Invoice invoice);

    boolean exists(String tenant, String externalRef);
}
