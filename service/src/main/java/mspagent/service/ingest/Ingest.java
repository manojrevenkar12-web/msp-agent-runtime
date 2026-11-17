package mspagent.service.ingest;

import mspagent.core.engine.Json;
import mspagent.core.engine.RuntimeStore;
import mspagent.core.event.Event;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts an event: records the delivery and, the first time, puts it in the outbox in the same transaction. A
 * webhook that is retried or sent twice is acknowledged and dropped here.
 */
@Component
public class Ingest {
    private final RuntimeStore store;
    private final JdbcClient jdbc;

    public Ingest(RuntimeStore store, JdbcClient jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    /** Events of one ticket (or one customer) share a partition, so the broker keeps them in order. */
    public static String partitionKey(Event e) {
        String k = e.data().get("ticketId");
        if (k == null) {
            k = e.data().get("customerId");
        }
        return e.tenant() + "/" + (k == null ? e.id() : k);
    }

    @Transactional
    public boolean accept(Event e) {
        boolean first = store.firstDelivery(e.tenant(), e.source(), e.id(), e.occurredAt());
        if (first) {
            jdbc.sql("insert into outbox (tenant, partition_key, event) values (:t, :k, cast(:e as jsonb))")
                    .param("t", e.tenant()).param("k", partitionKey(e)).param("e", Json.write(e)).update();
        }
        return first;
    }
}
