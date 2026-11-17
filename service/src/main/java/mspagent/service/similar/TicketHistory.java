package mspagent.service.similar;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mspagent.core.domain.Category;
import mspagent.core.domain.Priority;
import mspagent.core.port.Neighbour;
import mspagent.core.port.SimilarTickets;
import mspagent.core.port.Ticket;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Resolved tickets with their final category and priority, searched by similarity for triage. Uses pgvector's HNSW
 * index when the database has the extension; otherwise compares in Java against the tenant's latest 2,000 tickets.
 */
@Component
public class TicketHistory implements SimilarTickets {
    private final JdbcClient jdbc;
    private final HashingEmbedder embedder = new HashingEmbedder();
    private volatile Boolean pgvector;

    public TicketHistory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean usesPgvector() {
        if (pgvector == null) {
            pgvector = jdbc.sql("select count(*) from information_schema.columns where table_name = 'ticket_history' "
                    + "and column_name = 'embedding_v'").query(Long.class).single() > 0;
        }
        return pgvector;
    }

    static String literal(float[] v) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(v[i]);
        }
        return b.append(']').toString();
    }

    /** Adds a resolved ticket; skipped until triage has set its category and priority. */
    public void index(String tenant, Ticket t) {
        if (t.category() == null || t.priority() == null) {
            return;
        }
        float[] v = embedder.embed(t.subject() + "\n" + t.body());
        String lit = literal(v);
        String arr = "{" + lit.substring(1, lit.length() - 1) + "}";
        if (usesPgvector()) {
            jdbc.sql("""
                    insert into ticket_history (tenant, ticket_id, category, priority, embedding, embedding_v)
                    values (:t, :id, :c, :p, cast(:arr as real[]), cast(:vec as vector))
                    on conflict (tenant, ticket_id) do update set category = :c, priority = :p,
                       embedding = cast(:arr as real[]), embedding_v = cast(:vec as vector)""")
                    .param("t", tenant).param("id", t.id()).param("c", t.category().name())
                    .param("p", t.priority().name()).param("arr", arr).param("vec", lit).update();
        } else {
            jdbc.sql("""
                    insert into ticket_history (tenant, ticket_id, category, priority, embedding)
                    values (:t, :id, :c, :p, cast(:arr as real[]))
                    on conflict (tenant, ticket_id) do update set category = :c, priority = :p,
                       embedding = cast(:arr as real[])""")
                    .param("t", tenant).param("id", t.id()).param("c", t.category().name())
                    .param("p", t.priority().name()).param("arr", arr).update();
        }
    }

    @Override
    public List<Neighbour> nearest(String tenant, String text, int k) {
        float[] q = embedder.embed(text);
        if (usesPgvector()) {
            return jdbc.sql("""
                    select ticket_id, category, priority, 1 - (embedding_v <=> cast(:q as vector)) as sim
                      from ticket_history where tenant = :t order by embedding_v <=> cast(:q as vector) limit :k""")
                    .param("t", tenant).param("q", literal(q)).param("k", k)
                    .query((rs, n) -> new Neighbour(rs.getString("ticket_id"), rs.getDouble("sim"),
                            Category.valueOf(rs.getString("category")), Priority.valueOf(rs.getString("priority"))))
                    .list();
        }
        List<Neighbour> all = new ArrayList<>(jdbc.sql("""
                select ticket_id, category, priority, embedding from ticket_history where tenant = :t
                 order by ticket_id desc limit 2000""")
                .param("t", tenant)
                .query((rs, n) -> {
                    Array a = rs.getArray("embedding");
                    Object[] xs = (Object[]) a.getArray();
                    float[] v = new float[xs.length];
                    for (int i = 0; i < xs.length; i++) {
                        v[i] = ((Number) xs[i]).floatValue();
                    }
                    return new Neighbour(rs.getString("ticket_id"), HashingEmbedder.cosine(q, v),
                            Category.valueOf(rs.getString("category")), Priority.valueOf(rs.getString("priority")));
                }).list());
        all.sort(Comparator.comparingDouble(Neighbour::similarity).reversed());
        return all.subList(0, Math.min(k, all.size()));
    }
}
