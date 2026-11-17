package mspagent.core.domain;

/** First-response targets in minutes, by priority, as written in the customer's contract. */
public enum SlaTier {
    GOLD(30, 60, 240, 480),
    SILVER(60, 120, 480, 960),
    BRONZE(120, 240, 960, 1920);

    private final int[] responseMinutes;

    SlaTier(int... responseMinutes) {
        this.responseMinutes = responseMinutes;
    }

    public int responseMinutes(Priority p) {
        return responseMinutes[p.ordinal()];
    }
}
