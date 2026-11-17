package mspagent.core.port;

import mspagent.core.domain.WorkKind;

/**
 * A piece of work a model read from a note.
 *
 * @param startMinuteOfDay minutes after midnight (Berlin) when the note names a start time, otherwise null
 * @param quote the words of the note that state this work
 */
public record NoteInterval(Integer startMinuteOfDay, int minutes, WorkKind kind, String quote) {}
