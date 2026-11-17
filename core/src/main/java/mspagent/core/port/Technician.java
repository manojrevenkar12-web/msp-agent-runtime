package mspagent.core.port;

import java.util.Set;
import mspagent.core.domain.Category;

/** A technician with the categories they handle and their current open tickets. */
public record Technician(String id, String name, Set<Category> skills, int openTickets) {}
