package mspagent.service.bus;

import mspagent.core.event.Event;

/** Sends an accepted event to the broker under its partition key. */
public interface Publisher {
    void publish(String partitionKey, Event e);
}
