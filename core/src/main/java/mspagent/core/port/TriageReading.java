package mspagent.core.port;

import java.util.List;
import mspagent.core.domain.Category;
import mspagent.core.domain.Impact;
import mspagent.core.domain.Urgency;

/** What a model read from a new ticket. {@code evidence} must be quotes from the ticket text. */
public record TriageReading(Impact impact, Urgency urgency, Category category, List<String> evidence) {}
