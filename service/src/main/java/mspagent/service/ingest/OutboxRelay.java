package mspagent.service.ingest;

import java.util.List;
import mspagent.core.engine.Json;
import mspagent.core.event.Event;
import mspagent.service.Settings;
import mspagent.service.bus.Publisher;
import mspagent.service.runtime.Dispatcher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves accepted events from the outbox to the broker (bus {@code amqp}), or straight to the engine on a single node
 * (bus {@code inline}). Rows are locked with SKIP LOCKED, so several instances can relay side by side.
 */
@Component
public class OutboxRelay {
    private final JdbcClient jdbc;
    private final Settings settings;
    private final Publisher publisher;
    private final Dispatcher dispatcher;

    public OutboxRelay(JdbcClient jdbc, Settings settings, Publisher publisher, Dispatcher dispatcher) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.publisher = publisher;
        this.dispatcher = dispatcher;
    }

    record Row(long id, String key, String event) {}

    @Scheduled(fixedDelayString = "${msp.relay-millis:200}")
    public void relay() {
        while (relayBatch() > 0) {
            // drain
        }
    }

    @Transactional
    public int relayBatch() {
        List<Row> rows = jdbc.sql("select id, partition_key, event::text as event from outbox "
                        + "where published_at is null order by id limit 100 for update skip locked")
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("partition_key"), rs.getString("event")))
                .list();
        for (Row r : rows) {
            Event e = Json.read(r.event(), Event.class);
            if ("amqp".equals(settings.bus())) {
                publisher.publish(r.key(), e);
            } else {
                dispatcher.dispatch(e);
            }
            jdbc.sql("update outbox set published_at = now() where id = :id").param("id", r.id()).update();
        }
        return rows.size();
    }
}
