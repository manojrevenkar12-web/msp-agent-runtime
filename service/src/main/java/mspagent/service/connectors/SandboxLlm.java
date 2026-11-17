package mspagent.service.connectors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mspagent.core.domain.Category;
import mspagent.core.domain.Impact;
import mspagent.core.domain.Urgency;
import mspagent.core.domain.WorkKind;
import mspagent.core.port.Llm;
import mspagent.core.port.LlmCall;
import mspagent.core.port.NoteInterval;
import mspagent.core.port.NotesReading;
import mspagent.core.port.TriageReading;
import mspagent.core.port.UpdateRequest;

/**
 * A rule-based stand-in for a model, so the sandbox runs without network or keys. It answers in the same shapes a
 * model does and goes through the same checks. Use {@code msp.llm.mode: openai} for a real model.
 */
public final class SandboxLlm implements Llm {
    private static final String MODEL = "sandbox-rules";
    private static final Pattern MINUTES = Pattern.compile("(\\d{1,3})\\s*(?:min|minuten|min\\.)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOURS = Pattern.compile("(\\d{1,2})(?:[,.](\\d{1,2}))?\\s*(?:h|std)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})");

    private static String sentenceAround(String text, int at) {
        int s = Math.max(text.lastIndexOf('.', at - 1), Math.max(text.lastIndexOf(':', at - 1), -1)) + 1;
        int e = text.indexOf('.', at);
        String q = text.substring(s, e < 0 ? text.length() : e).trim();
        return q.isEmpty() ? text.trim() : q;
    }

    @Override
    public LlmCall<TriageReading> readTicket(String subject, String body) {
        String text = subject + "\n" + body;
        String low = text.toLowerCase(Locale.ROOT);
        List<String> evidence = new ArrayList<>();
        Impact impact = Impact.SINGLE_USER;
        for (String k : List.of("ganze firma", "alle mitarbeiter", "whole office")) {
            if (low.contains(k)) {
                impact = Impact.COMPANY;
                evidence.add(sentenceAround(text, low.indexOf(k)));
            }
        }
        if (impact == Impact.SINGLE_USER) {
            for (String k : List.of("team", "abteilung", "buchhaltung")) {
                if (low.contains(k)) {
                    impact = Impact.TEAM;
                    evidence.add(sentenceAround(text, low.indexOf(k)));
                    break;
                }
            }
        }
        Urgency urgency = Urgency.DEGRADED;
        for (String k : List.of("nicht arbeiten", "nichts geht", "cannot work", "down")) {
            if (low.contains(k)) {
                urgency = Urgency.WORK_STOPPED;
                evidence.add(sentenceAround(text, low.indexOf(k)));
                break;
            }
        }
        Category c = Category.INCIDENT;
        if (low.matches("(?s).*(einrichten|neuer|new laptop|install).*")) {
            c = Category.INSTALLATION;
        } else if (low.matches("(?s).*(konto|zugriff|berechtigung|access).*")) {
            c = Category.SERVICE_REQUEST;
        } else if (low.matches("(?s).*(virus|phishing|verdächtig|trojaner).*")) {
            c = Category.SECURITY_ALERT;
        } else if (low.matches("(?s).*(migration|umzug).*")) {
            c = Category.PROJECT;
        }
        return new LlmCall<>(new TriageReading(impact, urgency, c, evidence), MODEL, text.length() / 4 + 600, 60);
    }

    @Override
    public LlmCall<NotesReading> readNote(String text) {
        List<NoteInterval> out = new ArrayList<>();
        WorkKind kind = text.toLowerCase(Locale.ROOT).matches("(?s).*(vor ort|onsite).*") ? WorkKind.ONSITE
                : text.toLowerCase(Locale.ROOT).matches("(?s).*(telefon|rückruf|call).*") ? WorkKind.PHONE
                : WorkKind.REMOTE;
        Matcher r = RANGE.matcher(text);
        if (r.find()) {
            int s = Integer.parseInt(r.group(1)) * 60 + Integer.parseInt(r.group(2));
            int e = Integer.parseInt(r.group(3)) * 60 + Integer.parseInt(r.group(4));
            out.add(new NoteInterval(s, e - s, kind, r.group()));
        } else {
            Matcher m = MINUTES.matcher(text);
            Matcher h = HOURS.matcher(text);
            if (m.find()) {
                out.add(new NoteInterval(null, Integer.parseInt(m.group(1)), kind, m.group()));
            } else if (h.find()) {
                double hours = Double.parseDouble(h.group(1) + "." + (h.group(2) == null ? "0" : h.group(2)));
                out.add(new NoteInterval(null, (int) Math.round(hours * 60), kind, h.group()));
            }
        }
        return new LlmCall<>(new NotesReading(out), MODEL, text.length() / 4 + 500, 40);
    }

    @Override
    public LlmCall<String> draftUpdate(UpdateRequest r) {
        String text = switch (r.status()) {
            case "ASSIGNED" -> r.technicianName() + " kümmert sich jetzt um \"" + r.subject() + "\".";
            case "WAITING_ON_CUSTOMER" -> "Für \"" + r.subject() + "\" brauchen wir kurz Ihre Rückmeldung.";
            case "RESOLVED" -> "\"" + r.subject() + "\" ist erledigt. Melden Sie sich gern bei Fragen.";
            default -> "Es gibt Neuigkeiten zu \"" + r.subject() + "\".";
        };
        return new LlmCall<>(text, MODEL, 300, 40);
    }
}
