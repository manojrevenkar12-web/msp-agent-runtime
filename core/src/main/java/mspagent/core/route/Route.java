package mspagent.core.route;

/** Sends events of one type to one agent. */
public record Route(String eventType, String agentId) {}
