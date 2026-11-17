package mspagent.core.engine;

import java.time.Instant;

/** A step's result, kept so that a resumed run replays it instead of doing the work again. */
public record StepRecord(String runId, int seq, String name, String json, Instant at) {}
