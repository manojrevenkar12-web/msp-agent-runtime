package mspagent.core.agent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Text checks the agents' code applies to what a model returned. */
public final class Text {
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(passwor[td]|kennwort|pw:|pin:|\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b)");

    private Text() {}

    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.toLowerCase(Locale.ROOT)
                .replace('–', '-')
                .replace('—', '-')
                .replace('„', '"')
                .replace('“', '"')
                .replace('”', '"');
        return SPACE.matcher(t).replaceAll(" ").trim();
    }

    /** True when {@code quote} appears in {@code text}, ignoring case and spacing. */
    public static boolean quotes(String text, String quote) {
        String q = normalize(quote);
        return !q.isEmpty() && normalize(text).contains(q);
    }

    public static boolean containsSecret(String s) {
        return SECRET.matcher(s).find();
    }

    /**
     * True when {@code draft} repeats {@code n} or more consecutive words of any of {@code sources}, or the whole of a
     * source shorter than that (but at least three words).
     */
    public static boolean repeats(String draft, List<String> sources, int n) {
        for (String src : sources) {
            int k = Math.max(3, Math.min(n, words(src).length));
            Set<String> grams = shingles(src, k);
            for (String g : shingles(draft, k)) {
                if (grams.contains(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String[] words(String s) {
        String t = normalize(s).replaceAll("[^\\p{L}\\p{N} ]", " ").trim();
        return t.isEmpty() ? new String[0] : t.split(" +");
    }

    private static Set<String> shingles(String s, int n) {
        String[] w = words(s);
        Set<String> out = new HashSet<>();
        for (int i = 0; i + n <= w.length; i++) {
            out.add(String.join(" ", java.util.Arrays.copyOfRange(w, i, i + n)));
        }
        return out;
    }
}
