package mspagent.core.port;

/** A model's answer with what it cost in tokens. */
public record LlmCall<T>(T value, String model, int inputTokens, int outputTokens) {}
