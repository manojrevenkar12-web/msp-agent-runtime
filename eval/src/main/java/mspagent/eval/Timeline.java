package mspagent.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import mspagent.core.event.Event;
import mspagent.core.event.EventTypes;
import mspagent.world.Rand;
import mspagent.world.Truth.ManualPlan;
import mspagent.world.Truth.StatusChange;
import mspagent.world.Truth.TrueNote;
import mspagent.world.Truth.TrueSession;
import mspagent.world.Truth.TrueTicket;
import mspagent.world.World;
import mspagent.world.WorldSettings;
import mspagent.world.WorldSystems;

/**
 * Plays a month: world actions at their times, and the webhooks they cause delivered late, twice and out of order
 * like a real broker would. A design gets each delivery and may schedule its own work (retries, approvals).
 */
final class Timeline {
    interface Consumer {
        void deliver(Event e, Timeline t);

        default void monthClosed(Timeline t) {}
    }

    private record Item(Instant at, long seq, Runnable action) {}

    private final World w;
    final WorldSystems sys;
    final SimClock clock;
    private final PriorityQueue<Item> queue = new PriorityQueue<>((a, b) ->
            a.at().equals(b.at()) ? Long.compare(a.seq(), b.seq()) : a.at().compareTo(b.at()));
    private long seq;
    final Instant stopAt;
    int deliveries;
    int duplicateDeliveries;
    int lateDeliveries;

    Timeline(World w, WorldSystems sys, SimClock clock) {
        this.w = w;
        this.sys = sys;
        this.clock = clock;
        this.stopAt = w.monthEnd.plusSeconds(3 * 86_400);
    }

    void at(Instant t, Runnable r) {
        queue.add(new Item(t.isBefore(clock.instant()) ? clock.instant() : t, seq++, r));
    }

    void play(Consumer c) {
        for (TrueTicket t : w.tickets) {
            at(t.createdAt(), () -> {
                sys.open(t);
                emit(c, event("tickets", t.id() + ":created", EventTypes.TICKET_CREATED, t.createdAt(),
                        Map.of("ticketId", t.id())));
            });
        }
        for (TrueNote n : w.notes) {
            at(n.writtenAt(), () -> {
                sys.write(n);
                emit(c, event("tickets", n.id(), EventTypes.NOTE_ADDED, n.writtenAt(),
                        Map.of("ticketId", n.ticketId(), "noteId", n.id())));
            });
        }
        for (TrueSession s : w.sessions) {
            at(s.end(), () -> {
                sys.end(s);
                if (s.viaRmm()) {
                    emit(c, event("rmm", s.id(), EventTypes.SESSION_ENDED, s.end(),
                            Map.of("ticketId", s.ticketId(), "sessionId", s.id())));
                }
                if (s.checkedIn()) {
                    emit(c, event("field", s.id(), EventTypes.CHECKED_OUT, s.end(),
                            Map.of("ticketId", s.ticketId(), "checkInId", s.id())));
                }
            });
        }
        for (StatusChange sc : w.statusChanges) {
            at(sc.at(), () -> {
                sys.status(sc);
                emit(c, event("tickets", sc.id(), EventTypes.TICKET_STATUS, sc.at(),
                        Map.of("ticketId", sc.ticketId(), "status", sc.status())));
            });
        }
        for (ManualPlan p : w.manual) {
            at(p.enteredAt(), () -> sys.typeManual(p));
        }
        at(w.monthEnd.minusSeconds(3600), sys::confirmPending);
        at(w.monthEnd.plusSeconds(6 * 3600), () -> {
            c.monthClosed(this);
            for (String customer : w.contracts.keySet()) {
                emit(c, event("erp", "close:" + w.month + ":" + customer, EventTypes.MONTH_CLOSED,
                        clock.instant(), Map.of("customerId", customer, "month", w.month)));
            }
        });
        while (!queue.isEmpty()) {
            Item i = queue.poll();
            if (i.at().isAfter(stopAt)) {
                break;
            }
            clock.set(i.at());
            i.action().run();
        }
    }

    private Event event(String source, String id, String type, Instant at, Map<String, String> data) {
        return new Event(w.tenant, source, id, type, at, data);
    }

    /** Hands an event to the broker: delayed, sometimes late, sometimes twice. */
    void emit(Consumer c, Event e) {
        WorldSettings.Delivery d = w.settings.delivery();
        Rand r = Rand.of(w.seed, "delivery|" + e.key());
        double delay = r.exponential(d.meanDelayMinutes());
        if (r.chance(d.lateRate())) {
            delay += r.between(d.lateMinutes());
            lateDeliveries++;
        }
        schedule(c, e, clock.instant().plusSeconds((long) (delay * 60)));
        if (r.chance(d.duplicate())) {
            duplicateDeliveries++;
            schedule(c, e, clock.instant().plusSeconds((long) (delay * 60) + 60L * r.between(1, 90)));
        }
    }

    void schedule(Consumer c, Event e, Instant at) {
        at(at, () -> {
            deliveries++;
            c.deliver(e, this);
        });
    }

    static List<Instant> sorted(List<Instant> xs) {
        List<Instant> out = new ArrayList<>(xs);
        out.sort(Instant::compareTo);
        return out;
    }
}
