package mspagent.core.port;

/** Everything an agent may reach outside the runtime. */
public record Tools(TicketSystem tickets, Rmm rmm, FieldApp field, Erp erp, Llm llm, SimilarTickets similar) {}
