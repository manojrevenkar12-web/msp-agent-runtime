package mspagent.core.event;

import java.time.Instant;
import java.util.Map;

/**
 * Something that happened in one of a service provider's systems: a ticket was opened, a technician wrote a note, a
 * remote session ended, a month closed. Events arrive at least once and in any order; {@code id} is the source
 * system's own id, so a redelivery carries the same one.
 */
public record Event(String tenant, String source, String id, String type, Instant occurredAt, Map<String, String> data) {

    public Event {
        data = Map.copyOf(data);
    }

    public String key() {
        return tenant + "/" + source + "/" + id;
    }

    public String get(String field) {
        String v = data.get(field);
        if (v == null) {
            throw new IllegalArgumentException("event " + key() + " has no " + field);
        }
        return v;
    }
}
