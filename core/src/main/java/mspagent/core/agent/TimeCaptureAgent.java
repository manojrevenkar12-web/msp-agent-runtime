package mspagent.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import mspagent.core.domain.EntrySource;
import mspagent.core.domain.EntryStatus;
import mspagent.core.domain.TimeEntry;
import mspagent.core.domain.WorkKind;
import mspagent.core.engine.Ctx;
import mspagent.core.engine.Hash;
import mspagent.core.event.EventTypes;
import mspagent.core.port.CheckIn;
import mspagent.core.port.Note;
import mspagent.core.port.NoteInterval;
import mspagent.core.port.NotesReading;
import mspagent.core.port.RemoteSession;
import mspagent.core.port.Ticket;

/**
 * Turns work into time entries so that worked minutes get billed: remote sessions from the RMM log, on-site visits
 * from the field app, and work a technician only wrote down in a note.
 *
 * <p>Measured sources become confirmed entries. A model reads notes; code accepts a piece of work only if the quoted
 * words are in the note, the duration is plausible and it happened while the ticket was open before the note was
 * written. Long pieces wait for the technician's confirmation. Work already covered by an entry, from any source, is
 * not entered again; a measured session replaces an earlier entry read from a note, so a minute is billed once.
 */
public final class TimeCaptureAgent implements Agent {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Override
    public String id() {
        return "time-capture";
    }

    @Override
    public String title() {
        return "Time capture";
    }

    @Override
    public String description() {
        return "Creates time entries from RMM sessions, on-site visits and technicians' notes, without double entries.";
    }

    @Override
    public List<String> eventTypes() {
        return List.of(EventTypes.NOTE_ADDED, EventTypes.SESSION_ENDED, EventTypes.CHECKED_OUT);
    }

    @Override
    public List<String> testedFields() {
        return List.of("voidIds");
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.integer("autoConfirmMinutes", 30, 0, 240,
                        "Work read from a note up to this many minutes is entered as confirmed"),
                ParamSpec.integer("maxNoteMinutes", 240, 15, 600, "Longest piece of work accepted from one note"),
                ParamSpec.integer("coveredPercent", 50, 10, 100,
                        "Overlap with an existing entry that counts as the same work (%)"),
                ParamSpec.integer("noteLagMinutes", 60, 0, 480,
                        "How long after the work a note may be written and still describe the same work"),
                ParamSpec.flag("readNotes", true, "Read technicians' notes with the model"));
    }

    public record Facts(Ticket ticket, List<TimeEntry> entries, List<RemoteSession> sessions, List<CheckIn> checkIns,
            List<Note> notes) {}

    public record Candidate(TimeEntry entry, String quote) {}

    public record Dropped(String what, String reason) {}

    public record Decision(List<TimeEntry> add, List<String> voidIds, List<Dropped> dropped) {}

    @Override
    public void run(Ctx ctx) {
        String ticketId = ctx.event().get("ticketId");
        Facts f = ctx.step("facts", Facts.class, () -> new Facts(
                ctx.tools().tickets().ticket(ctx.tenant(), ticketId),
                ctx.tools().tickets().entries(ctx.tenant(), ticketId),
                ctx.tools().rmm().sessions(ctx.tenant(), ticketId),
                ctx.tools().field().checkIns(ctx.tenant(), ticketId),
                ctx.tools().tickets().notes(ctx.tenant(), ticketId)));

        List<Candidate> candidates = new ArrayList<>();
        List<Dropped> dropped = new ArrayList<>();
        switch (ctx.event().type()) {
            case EventTypes.SESSION_ENDED -> {
                String id = ctx.event().get("sessionId");
                f.sessions().stream().filter(s -> s.id().equals(id)).findFirst().ifPresentOrElse(
                        s -> candidates.add(new Candidate(measured(f.ticket(), s.technicianId(), s.start(), s.end(),
                                WorkKind.REMOTE, EntrySource.RMM, "te:rmm:" + s.id()), null)),
                        () -> dropped.add(new Dropped("session " + id, "not in the RMM log")));
            }
            case EventTypes.CHECKED_OUT -> {
                String id = ctx.event().get("checkInId");
                f.checkIns().stream().filter(c -> c.id().equals(id)).findFirst().ifPresentOrElse(
                        c -> candidates.add(new Candidate(measured(f.ticket(), c.technicianId(), c.start(), c.end(),
                                WorkKind.ONSITE, EntrySource.CHECKIN, "te:checkin:" + c.id()), null)),
                        () -> dropped.add(new Dropped("visit " + id, "not in the field app")));
            }
            case EventTypes.NOTE_ADDED -> {
                if (!ctx.config().flag("readNotes")) {
                    break;
                }
                String noteId = ctx.event().get("noteId");
                Note note = f.notes().stream().filter(n -> n.id().equals(noteId)).findFirst().orElse(null);
                if (note == null) {
                    dropped.add(new Dropped("note " + noteId, "not on the ticket"));
                    break;
                }
                NotesReading r = ctx.step("reading", NotesReading.class,
                        () -> ctx.llm("time.read_note", () -> ctx.tools().llm().readNote(note.text())).value());
                candidates.addAll(fromNote(ctx.config(), f.ticket(), note, r, dropped));
            }
            default -> dropped.add(new Dropped(ctx.event().type(), "not a time event"));
        }

        Decision d = ctx.step("decision", Decision.class,
                () -> reconcile(ctx.config(), f.entries(), candidates, dropped));
        for (String v : d.voidIds()) {
            String ref = "void:" + v;
            ctx.effect(ref, "tickets.voidEntry", () -> ctx.tools().tickets().exists(ctx.tenant(), ref),
                    () -> ctx.tools().tickets().voidEntry(ctx.tenant(), v, ref));
        }
        for (TimeEntry e : d.add()) {
            ctx.effect(e.externalRef(), "tickets.addEntry",
                    () -> ctx.tools().tickets().exists(ctx.tenant(), e.externalRef()),
                    () -> ctx.tools().tickets().addEntry(ctx.tenant(), e));
        }
    }

    private static TimeEntry measured(Ticket t, String tech, Instant start, Instant end, WorkKind kind,
            EntrySource src, String ref) {
        int minutes = (int) Math.max(1, (Duration.between(start, end).getSeconds() + 59) / 60);
        return new TimeEntry(Hash.short64(ref), t.id(), t.customerId(), tech, start, minutes, kind, src,
                EntryStatus.CONFIRMED, ref);
    }

    static List<Candidate> fromNote(AgentConfig cfg, Ticket t, Note n, NotesReading r, List<Dropped> dropped) {
        List<Candidate> out = new ArrayList<>();
        int i = 0;
        for (NoteInterval iv : r.intervals()) {
            i++;
            String what = "note " + n.id() + " #" + i;
            if (iv.quote() == null || !Text.quotes(n.text(), iv.quote())) {
                dropped.add(new Dropped(what, "quote is not in the note"));
                continue;
            }
            if (!iv.quote().matches(".*\\d.*")) {
                dropped.add(new Dropped(what, "the quoted words state no duration"));
                continue;
            }
            if (iv.minutes() < 1 || iv.minutes() > cfg.integer("maxNoteMinutes")) {
                dropped.add(new Dropped(what, iv.minutes() + " minutes is not plausible"));
                continue;
            }
            Instant start;
            if (iv.startMinuteOfDay() != null) {
                LocalDate day = n.writtenAt().atZone(BERLIN).toLocalDate();
                start = day.atStartOfDay(BERLIN).plusMinutes(iv.startMinuteOfDay()).toInstant();
            } else {
                start = n.writtenAt().minusSeconds(60L * iv.minutes());
            }
            Instant end = start.plusSeconds(60L * iv.minutes());
            if (start.isBefore(t.createdAt().minusSeconds(300)) || end.isAfter(n.writtenAt().plusSeconds(300))) {
                dropped.add(new Dropped(what, "outside the time the ticket was open before the note"));
                continue;
            }
            EntryStatus status = iv.minutes() <= cfg.integer("autoConfirmMinutes")
                    ? EntryStatus.CONFIRMED : EntryStatus.PENDING;
            String ref = "te:note:" + n.id() + ":" + i;
            out.add(new Candidate(new TimeEntry(Hash.short64(ref), t.id(), t.customerId(), n.technicianId(), start,
                    iv.minutes(), iv.kind(), EntrySource.NOTE, status, ref), iv.quote()));
        }
        return out;
    }

    /**
     * Two entries stand for the same work when they overlap enough, or when one was read from a note (whose times are
     * only as exact as the note) and the other ended within the note lag of it, with the same kind of work or a
     * similar duration.
     */
    static boolean sameWork(TimeEntry a, TimeEntry b, int pct, int lagMinutes) {
        if (a.overlapMinutes(b.start(), b.end()) * 100L >= (long) pct * Math.min(a.minutes(), b.minutes())) {
            return true;
        }
        if (a.source() != EntrySource.NOTE && b.source() != EntrySource.NOTE) {
            return false;
        }
        long gap = Math.abs(a.end().getEpochSecond() - b.end().getEpochSecond()) / 60;
        double ratio = (double) Math.max(a.minutes(), b.minutes()) / Math.max(1, Math.min(a.minutes(), b.minutes()));
        return gap <= lagMinutes && (a.kind() == b.kind() || ratio <= 2.0);
    }

    static Decision reconcile(AgentConfig cfg, List<TimeEntry> existing, List<Candidate> candidates,
            List<Dropped> dropped) {
        List<TimeEntry> add = new ArrayList<>();
        List<String> voidIds = new ArrayList<>();
        List<TimeEntry> live = new ArrayList<>(existing.stream().filter(e -> e.status() != EntryStatus.VOID).toList());
        int pct = cfg.integer("coveredPercent");
        for (Candidate c : candidates) {
            TimeEntry e = c.entry();
            if (live.stream().anyMatch(x -> x.externalRef() != null && x.externalRef().equals(e.externalRef()))) {
                dropped.add(new Dropped(e.externalRef(), "already entered"));
                continue;
            }
            int lag = cfg.integer("noteLagMinutes");
            List<TimeEntry> covering = live.stream()
                    .filter(x -> x.technicianId().equals(e.technicianId()))
                    .filter(x -> sameWork(x, e, pct, lag))
                    .toList();
            boolean measured = e.source() == EntrySource.RMM || e.source() == EntrySource.CHECKIN;
            if (!covering.isEmpty()) {
                boolean onlyNotes = covering.stream().allMatch(x -> x.source() == EntrySource.NOTE);
                if (measured && onlyNotes) {
                    covering.forEach(x -> voidIds.add(x.id()));
                    live.removeAll(covering);
                } else {
                    dropped.add(new Dropped(e.externalRef(), "covered by " + covering.get(0).source() + " entry "
                            + covering.get(0).id()));
                    continue;
                }
            }
            add.add(e);
            live.add(e);
        }
        return new Decision(add, voidIds, dropped);
    }
}
