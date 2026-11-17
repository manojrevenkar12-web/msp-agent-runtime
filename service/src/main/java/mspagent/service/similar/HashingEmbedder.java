package mspagent.service.similar;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Turns a ticket into a 256-dimensional vector by hashing its words and word pairs. Deterministic and local, so
 * nothing leaves the tenant for similarity search; a hosted embedding model can replace it behind the same method.
 */
public final class HashingEmbedder {
    public static final int DIM = 256;

    public float[] embed(String text) {
        float[] v = new float[DIM];
        String[] w = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").trim().split(" +");
        for (int i = 0; i < w.length; i++) {
            add(v, w[i], 1f);
            if (i + 1 < w.length) {
                add(v, w[i] + " " + w[i + 1], 0.5f);
            }
        }
        double n = 0;
        for (float x : v) {
            n += x * x;
        }
        n = Math.sqrt(n);
        if (n > 0) {
            for (int i = 0; i < DIM; i++) {
                v[i] = (float) (v[i] / n);
            }
        }
        return v;
    }

    private static void add(float[] v, String token, float weight) {
        CRC32 c = new CRC32();
        c.update(token.getBytes(StandardCharsets.UTF_8));
        long h = c.getValue();
        v[(int) (h % DIM)] += (h & 0x100000000L) == 0 && ((h >> 9) & 1) == 0 ? weight : -weight;
    }

    public static double cosine(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
