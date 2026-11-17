package mspagent.service.connectors;

import java.util.List;
import java.util.Map;
import mspagent.core.domain.Contract;
import mspagent.core.domain.Invoice;
import mspagent.core.domain.TimeEntry;
import mspagent.core.engine.Json;
import mspagent.core.engine.PermanentFailure;
import mspagent.core.port.CheckIn;
import mspagent.core.port.Erp;
import mspagent.core.port.FieldApp;
import mspagent.core.port.Note;
import mspagent.core.port.RemoteSession;
import mspagent.core.port.Rmm;
import mspagent.core.port.Technician;
import mspagent.core.port.Ticket;
import mspagent.core.port.TicketSystem;
import mspagent.core.port.TriageDecision;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;

/**
 * The service provider's systems behind one connector API (docs/connectors.md): a thin adapter a ticket system, RMM or
 * ERP vendor implements once. Every write carries its idempotency key in {@code Idempotency-Key}; {@code GET
 * /writes/{key}} answers whether a write landed. A 404 for a ticket or customer fails the run; other errors are retried.
 */
public final class HttpConnectors implements TicketSystem, Rmm, FieldApp, Erp {
    private final RestClient http;

    public HttpConnectors(RestClient http) {
        this.http = http;
    }

    private <T> T get(String tenant, String path, TypeReference<T> type, Object... vars) {
        try {
            String body = http.get().uri(path, vars).header("X-Tenant", tenant).retrieve().body(String.class);
            return Json.MAPPER.readValue(body, type);
        } catch (HttpClientErrorException.NotFound e) {
            throw new PermanentFailure("not found: " + path);
        }
    }

    private void write(String tenant, String method, String path, String key, Object body, Object... vars) {
        http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path, vars)
                .header("X-Tenant", tenant).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).body(Json.write(body)).retrieve().toBodilessEntity();
    }

    @Override
    public Ticket ticket(String tenant, String id) {
        return get(tenant, "/tickets/{id}", new TypeReference<Ticket>() {}, id);
    }

    @Override
    public List<Note> notes(String tenant, String id) {
        return get(tenant, "/tickets/{id}/notes", new TypeReference<List<Note>>() {}, id);
    }

    @Override
    public List<TimeEntry> entries(String tenant, String id) {
        return get(tenant, "/tickets/{id}/time-entries", new TypeReference<List<TimeEntry>>() {}, id);
    }

    @Override
    public List<TimeEntry> confirmedEntries(String tenant, String customerId, String month) {
        return get(tenant, "/customers/{id}/time-entries?month={m}&status=CONFIRMED",
                new TypeReference<List<TimeEntry>>() {}, customerId, month);
    }

    @Override
    public List<Technician> technicians(String tenant) {
        return get(tenant, "/technicians", new TypeReference<List<Technician>>() {});
    }

    @Override
    public void applyTriage(String tenant, TriageDecision d) {
        write(tenant, "PUT", "/tickets/{id}/triage", d.externalRef(), d, d.ticketId());
    }

    @Override
    public void addEntry(String tenant, TimeEntry e) {
        write(tenant, "POST", "/tickets/{id}/time-entries", e.externalRef(), e, e.ticketId());
    }

    @Override
    public void voidEntry(String tenant, String entryId, String key) {
        write(tenant, "POST", "/time-entries/{id}/void", key, Map.of(), entryId);
    }

    @Override
    public void postUpdate(String tenant, String ticketId, String text, String key) {
        write(tenant, "POST", "/tickets/{id}/updates", key, Map.of("text", text), ticketId);
    }

    @Override
    public boolean exists(String tenant, String key) {
        HttpStatusCode s = http.get().uri("/writes/{k}", key).header("X-Tenant", tenant).retrieve()
                .onStatus(c -> c.value() == 404, (req, res) -> { })
                .toBodilessEntity().getStatusCode();
        return s.is2xxSuccessful();
    }

    @Override
    public List<RemoteSession> sessions(String tenant, String ticketId) {
        return get(tenant, "/tickets/{id}/remote-sessions", new TypeReference<List<RemoteSession>>() {}, ticketId);
    }

    @Override
    public List<CheckIn> checkIns(String tenant, String ticketId) {
        return get(tenant, "/tickets/{id}/visits", new TypeReference<List<CheckIn>>() {}, ticketId);
    }

    @Override
    public Contract contract(String tenant, String customerId) {
        return get(tenant, "/customers/{id}/contract", new TypeReference<Contract>() {}, customerId);
    }

    @Override
    public void createInvoice(String tenant, Invoice invoice) {
        write(tenant, "POST", "/invoices", invoice.externalRef(), invoice);
    }
}
