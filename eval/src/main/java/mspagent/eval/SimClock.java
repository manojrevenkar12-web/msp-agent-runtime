package mspagent.eval;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** The simulation's clock; the timeline moves it forward. */
final class SimClock extends Clock {
    private Instant now;

    SimClock(Instant start) {
        this.now = start;
    }

    void set(Instant t) {
        if (t.isAfter(now)) {
            now = t;
        }
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
