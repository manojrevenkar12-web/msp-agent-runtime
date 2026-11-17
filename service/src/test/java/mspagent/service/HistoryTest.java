package mspagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import mspagent.core.domain.Category;
import mspagent.core.domain.Priority;
import mspagent.core.port.Neighbour;
import mspagent.core.port.Ticket;
import mspagent.service.similar.TicketHistory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Resolved tickets are found again by similarity (pgvector when the database has it, Java otherwise). */
class HistoryTest extends ServiceTest {
    @Autowired
    TicketHistory history;

    static Ticket t(String id, String subject, String body, Category c) {
        return new Ticket(id, "C1001", subject, body, Instant.now(), "RESOLVED", c, Priority.P3, "tech-1", Instant.now());
    }

    @Test
    void theMostSimilarResolvedTicketComesFirst() {
        String tenant = "history-" + System.nanoTime();
        history.index(tenant, t("H1", "Neuer Drucker", "bitte den neuen Drucker im Büro einrichten", Category.INSTALLATION));
        history.index(tenant, t("H2", "VPN verbindet nicht", "der VPN-Client meldet Zertifikatsfehler", Category.INCIDENT));
        history.index(tenant, t("H3", "Konto anlegen", "bitte ein Konto für die neue Kollegin", Category.SERVICE_REQUEST));
        List<Neighbour> n = history.nearest(tenant, "Drucker einrichten\nder neue Drucker im Lager soll eingerichtet werden", 2);
        assertEquals("H1", n.get(0).ticketId());
        assertEquals(Category.INSTALLATION, n.get(0).category());
        assertTrue(n.get(0).similarity() > n.get(1).similarity());
    }
}
