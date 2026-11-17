package mspagent.core.port;

/**
 * The language model as the agents use it: it reads and drafts. It never decides an amount, a priority or whether a
 * minute is billed; the agents' code does.
 */
public interface Llm {

    LlmCall<TriageReading> readTicket(String subject, String body);

    LlmCall<NotesReading> readNote(String text);

    LlmCall<String> draftUpdate(UpdateRequest request);
}
