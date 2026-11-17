package db.migration;

import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Adds a pgvector column and an HNSW index to the ticket history when the database offers the extension. Without it
 * the runtime computes similarity in Java over the tenant's latest tickets.
 */
public class V2__ticket_vectors extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (Statement s = context.getConnection().createStatement()) {
            boolean available;
            try (ResultSet r = s.executeQuery("select 1 from pg_available_extensions where name = 'vector'")) {
                available = r.next();
            }
            if (!available) {
                return;
            }
            s.execute("create extension if not exists vector");
            s.execute("alter table ticket_history add column embedding_v vector(256)");
            s.execute("create index ticket_history_hnsw on ticket_history using hnsw (embedding_v vector_cosine_ops)");
        }
    }
}
