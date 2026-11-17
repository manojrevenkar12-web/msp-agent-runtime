package mspagent.world;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import mspagent.core.engine.Hash;

/**
 * Deterministic randomness. {@link #of} draws from a stream keyed by what is being decided ("does technician 4
 * confirm entry X"), so every design meets the same luck for the same decision.
 */
public final class Rand {
    private final SplittableRandom r;

    public Rand(long seed) {
        this.r = new SplittableRandom(seed);
    }

    public static Rand of(long seed, String key) {
        return new Rand(seed * 1_000_003L ^ Long.parseUnsignedLong(Hash.sha256(key).substring(0, 15), 16));
    }

    public double unit() {
        return r.nextDouble();
    }

    public boolean chance(double p) {
        return r.nextDouble() < p;
    }

    public int between(int lo, int hiInclusive) {
        return lo + r.nextInt(hiInclusive - lo + 1);
    }

    public int between(List<Integer> range) {
        return between(range.get(0), range.get(1));
    }

    public double between(double lo, double hi) {
        return lo + (hi - lo) * r.nextDouble();
    }

    public <T> T pick(List<T> xs) {
        return xs.get(r.nextInt(xs.size()));
    }

    /** Picks a key with probability proportional to its weight. */
    public String weighted(Map<String, Double> weights) {
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double x = r.nextDouble() * total;
        String last = null;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            last = e.getKey();
            x -= e.getValue();
            if (x < 0) {
                return e.getKey();
            }
        }
        return last;
    }

    /** A lognormal draw with the given median, clipped. */
    public int lognormal(double median, double sigma, int min, int max) {
        double z = r.nextGaussian();
        int v = (int) Math.round(median * Math.exp(sigma * z));
        return Math.max(min, Math.min(max, v));
    }

    public double exponential(double mean) {
        return -mean * Math.log(1 - r.nextDouble());
    }
}
