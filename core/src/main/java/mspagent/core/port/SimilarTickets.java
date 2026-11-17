package mspagent.core.port;

import java.util.List;

/** Embedding search over a tenant's resolved tickets (pgvector in the service). */
public interface SimilarTickets {
    List<Neighbour> nearest(String tenant, String text, int k);
}
