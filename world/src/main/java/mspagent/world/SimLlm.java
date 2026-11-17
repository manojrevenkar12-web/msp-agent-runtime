package mspagent.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mspagent.core.domain.Category;
import mspagent.core.domain.Impact;
import mspagent.core.domain.Urgency;
import mspagent.core.domain.WorkKind;
import mspagent.core.port.Llm;
import mspagent.core.port.LlmCall;
import mspagent.core.port.LlmUnavailable;
import mspagent.core.port.Note;
import mspagent.core.port.NoteInterval;
import mspagent.core.port.NotesReading;
import mspagent.core.port.TriageReading;
import mspagent.core.port.UpdateRequest;
import mspagent.world.Truth.Segment;
import mspagent.world.Truth.TrueNote;
import mspagent.world.Truth.TrueTicket;

/**
 * A simulated language model with hidden error rates (config/world.json, "model"). It reads the same text a real
 * model would get and answers the same way for the same text, like a model at temperature 0. It obeys some prompt
 * injections, invents some quotes, misreads some durations, copies some internal notes into customer drafts, and is
 * sometimes unavailable.
 */
public final class SimLlm implements Llm {
    private final World w;
    private final WorldSettings.Model m;
    private final Map<String, Integer> calls = new HashMap<>();
    public int callCount;
    public int unavailableCount;

    public SimLlm(World w) {
        this.w = w;
        this.m = w.settings.model();
    }

    private void availability(String key) {
        callCount++;
        int n = calls.merge(key, 1, Integer::sum);
        if (Rand.of(w.seed, "unavailable|" + key + "|" + n).chance(m.unavailable())) {
            unavailableCount++;
            throw new LlmUnavailable("simulated 429 for " + key);
        }
    }

    private int tokens(String text) {
        return m.promptTokens() + text.length() / 4;
    }

    @Override
    public LlmCall<TriageReading> readTicket(String subject, String body) {
        String text = subject + "\n" + body;
        TrueTicket t = w.ticketByText.get(text);
        availability("ticket|" + text.hashCode());
        if (t == null) {
            return new LlmCall<>(new TriageReading(Impact.SINGLE_USER, Urgency.DEGRADED, Category.INCIDENT, List.of()),
                    m.name(), tokens(text), 60);
        }
        Rand r = Rand.of(w.seed, "read-ticket|" + t.id());
        Category c = r.chance(m.categoryRight()) ? t.category() : t.confusable();
        Impact i = r.chance(m.impactRight()) ? t.impact() : Impact.values()[r.between(0, 2)];
        Urgency u = r.chance(m.impactRight()) ? t.urgency() : Urgency.values()[r.between(0, 2)];
        List<String> evidence = new ArrayList<>(t.evidence());
        if (r.chance(m.hallucinatedQuote())) {
            evidence.set(0, "das ganze Unternehmen steht still");
        }
        if (t.injection() != null && r.chance(m.obeysInjection())) {
            i = Impact.COMPANY;
            u = Urgency.WORK_STOPPED;
            String inj = t.injection();
            evidence = List.of(inj.endsWith("!") || inj.endsWith(".") ? inj.substring(0, inj.length() - 1) : inj);
        }
        return new LlmCall<>(new TriageReading(i, u, c, evidence), m.name(), tokens(text), 70 + 10 * evidence.size());
    }

    @Override
    public LlmCall<NotesReading> readNote(String text) {
        TrueNote n = w.noteByText.get(text);
        availability("note|" + text.hashCode());
        if (n == null) {
            return new LlmCall<>(new NotesReading(List.of()), m.name(), tokens(text), 30);
        }
        Rand r = Rand.of(w.seed, "read-note|" + n.id());
        List<NoteInterval> out = new ArrayList<>();
        for (Segment s : n.segments()) {
            if (s.distractor()) {
                if (r.chance(m.readsDistractorAsWork())) {
                    int hours = Integer.parseInt(s.text().replaceAll("\\D+", " ").trim().split(" ")[0]);
                    out.add(new NoteInterval(null, hours * 60, WorkKind.REMOTE, s.quote()));
                }
                continue;
            }
            if (s.sessionId() == null) {
                continue;
            }
            if (!s.quote().matches(".*\\d.*")) {
                // No duration written: the model sometimes guesses one.
                if (r.chance(0.2)) {
                    out.add(new NoteInterval(null, 15, s.kind(), s.quote()));
                }
                continue;
            }
            if (r.chance(m.missesInterval())) {
                continue;
            }
            boolean right = r.chance(s.unseenFormat() ? m.unseenFormatRight() : m.durationRight());
            int minutes = right ? s.minutes() : misread(r, s.minutes());
            WorkKind kind = r.chance(m.kindRight()) ? s.kind() : WorkKind.values()[r.between(0, 2)];
            out.add(new NoteInterval(right ? s.startMinute() : null, minutes, kind, s.quote()));
        }
        return new LlmCall<>(new NotesReading(out), m.name(), tokens(text), 40 + 35 * out.size());
    }

    private static int misread(Rand r, int minutes) {
        return switch (r.between(0, 3)) {
            case 0 -> minutes * 10;
            case 1 -> Math.max(1, minutes / 2);
            case 2 -> minutes + 60;
            default -> Math.max(1, minutes / 10);
        };
    }

    @Override
    public LlmCall<String> draftUpdate(UpdateRequest req) {
        availability("update|" + req.ticketId() + "|" + req.status());
        Rand r = Rand.of(w.seed, "draft|" + req.ticketId() + "|" + req.status());
        String text = switch (req.status()) {
            case "ASSIGNED" -> "Guten Tag, " + req.technicianName() + " kümmert sich jetzt um Ihr Anliegen \""
                    + req.subject() + "\" und meldet sich in Kürze.";
            case "WAITING_ON_CUSTOMER" -> "Guten Tag, für Ihr Anliegen \"" + req.subject()
                    + "\" benötigen wir noch eine kurze Rückmeldung von Ihnen.";
            case "RESOLVED" -> "Guten Tag, Ihr Anliegen \"" + req.subject() + "\" ist erledigt. Bei Fragen melden Sie "
                    + "sich gern.";
            default -> "Guten Tag, es gibt Neuigkeiten zu \"" + req.subject() + "\".";
        };
        List<Note> internal = req.notes().stream().filter(Note::internal).toList();
        if (!internal.isEmpty()) {
            TrueNote tn = w.noteByText.get(internal.get(0).text());
            if (tn != null && !tn.secrets().isEmpty() && r.chance(m.leaksSecret() * 10)) {
                text += " Zur Info: " + tn.secrets().get(0) + ".";
            } else if (r.chance(m.leaksInternal())) {
                text += " Details: " + internal.get(0).text();
            }
        }
        return new LlmCall<>(text, m.name(), tokens(req.subject()) + 80, 60);
    }
}
