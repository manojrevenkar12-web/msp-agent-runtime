package mspagent.world;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mspagent.core.domain.Category;
import mspagent.core.domain.Contract;
import mspagent.core.domain.ContractType;
import mspagent.core.domain.Impact;
import mspagent.core.domain.Priority;
import mspagent.core.domain.SlaTier;
import mspagent.core.domain.Urgency;
import mspagent.core.domain.WorkKind;
import mspagent.core.port.Technician;
import mspagent.world.Truth.HistoryTicket;
import mspagent.world.Truth.ManualPlan;
import mspagent.world.Truth.Segment;
import mspagent.world.Truth.StatusChange;
import mspagent.world.Truth.TrueNote;
import mspagent.world.Truth.TrueSession;
import mspagent.world.Truth.TrueTicket;

/**
 * One simulated month at an IT service provider: customers and contracts, tickets, the work technicians really did,
 * what they wrote and typed. Generated once per seed; every design then runs on the same month.
 */
public final class World {
    public static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    public final WorldSettings settings;
    public final long seed;
    public final boolean heldOut;
    public final String tenant;
    public final String month;
    public final Map<String, Contract> contracts = new LinkedHashMap<>();
    public final List<Technician> technicians = new ArrayList<>();
    public final List<TrueTicket> tickets = new ArrayList<>();
    public final Map<String, TrueTicket> ticketById = new HashMap<>();
    public final Map<String, TrueTicket> ticketByText = new HashMap<>();
    public final List<TrueSession> sessions = new ArrayList<>();
    public final List<TrueNote> notes = new ArrayList<>();
    public final Map<String, TrueNote> noteByText = new HashMap<>();
    public final List<ManualPlan> manual = new ArrayList<>();
    public final List<StatusChange> statusChanges = new ArrayList<>();
    public final Map<String, List<HistoryTicket>> history = new HashMap<>();
    public final Instant monthStart;
    public final Instant monthEnd;

    private World(WorldSettings s, long seed, boolean heldOut) {
        this.settings = s;
        this.seed = seed;
        this.heldOut = heldOut;
        this.tenant = s.tenant();
        this.month = s.month();
        YearMonth ym = YearMonth.parse(s.month());
        this.monthStart = ym.atDay(1).atStartOfDay(BERLIN).toInstant();
        this.monthEnd = ym.plusMonths(1).atDay(1).atStartOfDay(BERLIN).toInstant();
    }

    public static World generate(WorldSettings s, long seed, boolean heldOut) {
        World w = new World(s, seed, heldOut);
        w.build();
        return w;
    }

    public static String monthOf(Instant t) {
        return YearMonth.from(t.atZone(BERLIN)).toString();
    }

    private void build() {
        Rand r = new Rand(seed);
        String[] names = {"Jonas", "Lea", "Mehmet", "Sophie", "Lukas", "Anna", "Tim", "Marie", "Felix", "Laura",
            "Paul", "Hannah", "Ben", "Emma", "Noah", "Mia"};
        for (int i = 0; i < settings.technicians(); i++) {
            Set<Category> skills = EnumSet.of(Category.INCIDENT, Category.SERVICE_REQUEST);
            if (i % 2 == 0) {
                skills.add(Category.INSTALLATION);
            }
            if (i % 3 == 0) {
                skills.add(Category.PROJECT);
            }
            if (i % 4 != 3) {
                skills.add(Category.SECURITY_ALERT);
            }
            technicians.add(new Technician("tech-" + (i + 1), names[i % names.length], skills, 0));
        }
        String[] firms = {"Kanzlei", "Praxis", "Autohaus", "Steuerbüro", "Bäckerei", "Ingenieurbüro", "Spedition",
            "Architekten", "Hotel", "Apotheke"};
        String[] towns = {"Hannover", "Celle", "Hildesheim", "Peine", "Garbsen", "Laatzen", "Langenhagen", "Wunstorf"};
        for (int i = 0; i < settings.customers(); i++) {
            ContractType type = ContractType.valueOf(r.weighted(settings.contractMix()));
            SlaTier sla = SlaTier.valueOf(r.weighted(settings.slaMix()));
            long rate = 100L * (r.between(settings.hourlyRateEuros()) / 5 * 5);
            long flat = type == ContractType.MANAGED_FLAT ? 100L * (r.between(settings.flatFeeEuros()) / 50 * 50) : 0;
            int prepaid = type == ContractType.PREPAID_HOURS ? r.between(settings.prepaidMinutes()) / 60 * 60 : 0;
            long travel = 100L * r.between(settings.travelFlatEuros());
            String id = "C" + (1001 + i);
            contracts.put(id, new Contract(id, firms[i % firms.length] + " " + towns[(i * 7) % towns.length] + " " + (i + 1),
                    type, sla, rate, flat, prepaid, travel));
        }
        for (Texts.Template t : Texts.TEMPLATES) {
            List<HistoryTicket> h = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                Category c = r.chance(0.9) ? t.category() : t.confusable();
                h.add(new HistoryTicket(t.id(), c, Priority.values()[Math.min(3, r.between(1, 3))]));
            }
            history.put(t.id(), h);
        }
        List<String> customerIds = new ArrayList<>(contracts.keySet());
        YearMonth ym = YearMonth.parse(month);
        int n = 0;
        for (LocalDate d = ym.atDay(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
            int count = weekend ? settings.ticketsPerWorkday() / 12 : r.between(
                    (int) (settings.ticketsPerWorkday() * 0.8), (int) (settings.ticketsPerWorkday() * 1.2));
            for (int k = 0; k < count; k++) {
                n++;
                ticket(r, "T" + (40000 + n), customerIds, d, weekend);
            }
        }
        tickets.sort(Comparator.comparing(TrueTicket::createdAt));
        statusChanges.sort(Comparator.comparing(StatusChange::at));
        manual.sort(Comparator.comparing(ManualPlan::enteredAt));
    }

    private void ticket(Rand r, String id, List<String> customerIds, LocalDate day, boolean weekend) {
        Category wanted = Category.valueOf(r.weighted(settings.categoryMix()));
        List<Texts.Template> fit = Texts.TEMPLATES.stream().filter(t -> t.category() == wanted).toList();
        Texts.Template tpl = r.pick(fit);
        Impact impact = Impact.values()[r.chance(0.12) ? 0 : r.chance(0.4) ? 1 : 2];
        Urgency urgency = Urgency.values()[r.chance(0.2) ? 0 : r.chance(0.55) ? 1 : 2];
        if (tpl.category() == Category.PROJECT) {
            urgency = Urgency.CAN_WAIT;
        }
        Priority priority = Priority.of(impact, urgency);
        String impactPhrase = r.pick(Texts.IMPACT.get(impact));
        String urgencyPhrase = r.pick(Texts.URGENCY.get(urgency));
        String injection = null;
        boolean vip = false;
        if (r.chance(settings.injectionRate())) {
            vip = heldOut && r.chance(0.5);
            injection = r.pick(vip ? Texts.VIP_INJECTIONS : Texts.INJECTIONS);
        }
        String subject = r.pick(tpl.subjects());
        String body = r.pick(Texts.GREETINGS) + " " + r.pick(tpl.problems()) + ". " + capital(impactPhrase) + ", "
                + urgencyPhrase + "." + (injection == null ? "" : " " + injection) + " " + r.pick(Texts.SIGNATURES);
        int minuteOfDay = weekend ? r.between(8 * 60, 20 * 60)
                : r.chance(0.75) ? r.between(8 * 60, 17 * 60) : r.between(6 * 60, 22 * 60);
        Instant created = day.atTime(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)).atZone(BERLIN).toInstant();
        String customer = r.pick(customerIds);
        List<Technician> able = technicians.stream().filter(t -> t.skills().contains(tpl.category())).toList();
        String worker = r.pick(able).id();

        int nominal = switch (priority) {
            case P1 -> r.between(10, 40);
            case P2 -> r.between(30, 120);
            case P3 -> r.between(90, 480);
            case P4 -> r.between(240, 1440);
        };
        Instant cursor = created.plusSeconds(60L * nominal);
        int sessionCount = r.between(settings.work().sessionsPerTicket());
        if (tpl.category() == Category.PROJECT) {
            sessionCount += 2;
        }
        Instant firstStart = null;
        for (int s = 0; s < sessionCount; s++) {
            WorkKind kind = WorkKind.valueOf(r.weighted(kinds(tpl)));
            double median = kind == WorkKind.PHONE ? 10 : tpl.medianMinutes();
            int minutes = r.lognormal(median, 0.55, 3, 360);
            Instant start = workingTime(cursor);
            Instant end = start.plusSeconds(60L * minutes);
            if (firstStart == null) {
                firstStart = start;
            }
            String sid = id + "-S" + (s + 1);
            boolean rmm = kind == WorkKind.REMOTE && r.chance(settings.work().remoteViaRmm());
            boolean checkin = kind == WorkKind.ONSITE && r.chance(settings.work().onsiteCheckedIn());
            TrueSession ts = new TrueSession(sid, id, worker, start, end, kind, rmm, checkin);
            sessions.add(ts);
            if (r.chance(settings.work().noteWritten())) {
                note(r, ts, tpl, s + 1);
            }
            Double p = settings.work().manualEntry().get(kind.name());
            if (p != null && r.chance(p)) {
                List<Double> u = settings.work().manualUnderRecord();
                int typed = Math.max(5, (int) Math.round(minutes * r.between(u.get(0), u.get(1)) / 5.0) * 5);
                Instant entered = end.plusSeconds(60L * r.between(10, 480));
                manual.add(new ManualPlan(sid, id, customer, worker, start, typed, kind, entered));
            }
            cursor = end.plusSeconds(60L * r.between(30, 60 * 26));
        }
        Instant closed = sessions.get(sessions.size() - 1).end().plusSeconds(60L * r.between(5, 120));
        TrueTicket tt = new TrueTicket(id, customer, tpl.id(), tpl.category(), tpl.confusable(), impact, urgency,
                priority, subject, body, List.of(impactPhrase, urgencyPhrase), injection, vip, created, closed, worker);
        tickets.add(tt);
        ticketById.put(id, tt);
        ticketByText.put(tt.text(), tt);
        statusChanges.add(new StatusChange(id + "-assigned", id, "ASSIGNED", firstStart.minusSeconds(60L * r.between(1, 10))));
        if (r.chance(0.15)) {
            statusChanges.add(new StatusChange(id + "-waiting", id, "WAITING_ON_CUSTOMER",
                    firstStart.plusSeconds(60L * r.between(20, 90))));
        }
        statusChanges.add(new StatusChange(id + "-resolved", id, "RESOLVED", closed));
    }

    private static Map<String, Double> kinds(Texts.Template t) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (WorkKind k : WorkKind.values()) {
            Double v = t.kinds().get(k);
            if (v != null) {
                m.put(k.name(), v);
            }
        }
        return m;
    }

    /** Moves a time into working hours (07:30 to 18:00 on weekdays). */
    private static Instant workingTime(Instant t) {
        ZonedDateTime z = t.atZone(BERLIN);
        for (int i = 0; i < 10; i++) {
            boolean weekend = z.getDayOfWeek() == DayOfWeek.SATURDAY || z.getDayOfWeek() == DayOfWeek.SUNDAY;
            int m = z.getHour() * 60 + z.getMinute();
            if (!weekend && m >= 7 * 60 + 30 && m < 18 * 60) {
                return z.toInstant();
            }
            if (!weekend && m < 7 * 60 + 30) {
                z = z.withHour(7).withMinute(30);
            } else {
                z = z.plusDays(1).withHour(7).withMinute(30);
            }
        }
        return z.toInstant();
    }

    private void note(Rand r, TrueSession ts, Texts.Template tpl, int k) {
        WorldSettings.Work w = settings.work();
        boolean internal = r.chance(w.internalNote());
        String action = r.pick(tpl.actions());
        boolean unseen = heldOut && r.chance(0.35);
        List<Segment> segs = new ArrayList<>();
        Instant written = ts.end().plusSeconds(60L * r.between(1, 40));
        String prefix = switch (ts.kind()) {
            case REMOTE -> unseen ? "Remote:" : r.pick(List.of("Fernwartung:", "Remote-Sitzung:"));
            case ONSITE -> unseen ? "Onsite:" : "Vor Ort:";
            case PHONE -> unseen ? "Call:" : r.pick(List.of("Telefonat:", "Rückruf:"));
        };
        if (r.chance(w.noteWithoutDuration())) {
            String q = action;
            segs.add(new Segment(prefix + " " + q + ".", q, ts.id(), ts.minutes(), ts.kind(), null, false, false));
        } else {
            int m = ts.minutes();
            Integer startMinute = null;
            String dur;
            if (unseen) {
                dur = m >= 60 && r.chance(0.5)
                        ? (m / 60) + " Std. " + (m % 60) + " Min."
                        : String.format(java.util.Locale.ROOT, "spent %.1fh", m / 60.0);
            } else {
                int f = r.between(0, 4);
                if (f == 3 && m % 15 != 0) {
                    f = 0;
                }
                ZonedDateTime zs = ts.start().atZone(BERLIN);
                ZonedDateTime ze = ts.end().atZone(BERLIN);
                dur = switch (f) {
                    case 0 -> "ca. " + m + " Min.";
                    case 1 -> m + "min";
                    case 2 -> m + " Minuten";
                    case 3 -> String.format(java.util.Locale.GERMANY, "%.2fh", m / 60.0).replace(",00h", "h")
                            .replace("0h", "h");
                    default -> String.format("%02d:%02d-%02d:%02d", zs.getHour(), zs.getMinute(), ze.getHour(),
                            ze.getMinute());
                };
                if (f == 4) {
                    startMinute = zs.getHour() * 60 + zs.getMinute();
                }
            }
            String q = action + ", " + dur;
            segs.add(new Segment(prefix + " " + q + ".", q, ts.id(), m, ts.kind(), startMinute, unseen, false));
        }
        if (r.chance(w.distractorRate())) {
            String d = String.format(r.pick(Texts.DISTRACTORS), r.between(2, 6));
            segs.add(new Segment(d, d.substring(0, d.length() - 1), null, 0, null, null, false, true));
        }
        List<String> secrets = new ArrayList<>();
        if (internal && r.chance(w.secretInInternalNote())) {
            String s = r.pick(Texts.SECRETS);
            secrets.add(s);
            segs.add(new Segment(s + ".", s, null, 0, null, null, false, false));
        }
        StringBuilder text = new StringBuilder();
        for (Segment s : segs) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(s.text());
        }
        TrueNote note = new TrueNote(ts.ticketId() + "-N" + k, ts.ticketId(), ts.technicianId(), written,
                text.toString(), internal, segs, secrets);
        notes.add(note);
        noteByText.put(note.text(), note);
    }

    private static String capital(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private Map<String, List<TrueSession>> byTicket;

    public List<TrueSession> sessionsOf(String ticketId) {
        if (byTicket == null) {
            byTicket = new HashMap<>();
            for (TrueSession s : sessions) {
                byTicket.computeIfAbsent(s.ticketId(), x -> new ArrayList<>()).add(s);
            }
        }
        return byTicket.getOrDefault(ticketId, List.of());
    }

    /** The minutes worked on a session that fall in the month, the only ones the month's invoice may carry. */
    public boolean inMonth(Instant t) {
        return !t.isBefore(monthStart) && t.isBefore(monthEnd);
    }
}
