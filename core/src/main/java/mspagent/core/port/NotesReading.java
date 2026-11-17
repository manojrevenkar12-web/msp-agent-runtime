package mspagent.core.port;

import java.util.List;

/** All pieces of work a model read from one note. */
public record NotesReading(List<NoteInterval> intervals) {}
