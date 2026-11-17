package mspagent.core.engine;

/** A model's list price in euro cents per million tokens. */
public record ModelPrice(long inputCentsPerMillion, long outputCentsPerMillion) {

    /** Cost in micro-euros (1e-6 €), exact for whole token counts. */
    public long costMicros(int inputTokens, int outputTokens) {
        return (inputTokens * inputCentsPerMillion + outputTokens * outputCentsPerMillion) / 100;
    }
}
