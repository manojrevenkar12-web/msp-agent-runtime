package mspagent.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import mspagent.core.event.Event;
import mspagent.core.trace.Span;
import org.junit.jupiter.api.Test;

/** What every {@link RuntimeStore} must do. The in-memory store and the PostgreSQL store both run these tests. */
public abstract class RuntimeStoreContract {
    protected static final Instant T0 = Instant.parse("2026-10-05T08:00:00Z");

    protected abstract RuntimeStore store();

    protected String tenant() {
        return "t-" + UUID.randomUUID();
    }

    protected Run run(String tenant, String id) {
        Event e = new Event(tenant, "tickets", "e-" + id, "ticket.created", T0, Map.of("ticketId", "T1"));
        return new Run(id, tenant, "triage", 1, e, RunStatus.PENDING, 0, null, null, null, T0, T0, null, false);
    }

    @Test
    public void aDeliveryIsFirstOnlyOnce() {
        String t = tenant();
        assertTrue(store().firstDelivery(t, "tickets", "e1", T0));
        assertFalse(store().firstDelivery(t, "tickets", "e1", T0));
        assertTrue(store().firstDelivery(tenant(), "tickets", "e1", T0), "tenants do not share deliveries");
    }

    @Test
    public void creatingARunTwiceKeepsTheFirst() {
        String t = tenant();
        Run a = store().createRun(run(t, "r1"));
        store().claim(t, "r1", "w1", T0, T0.plusSeconds(300));
        Run again = store().createRun(run(t, "r1"));
        assertEquals(RunStatus.RUNNING, again.status());
        assertEquals(a.event(), again.event());
    }

    @Test
    public void aLeaseKeepsOtherWorkersOutUntilItExpires() {
        String t = tenant();
        store().createRun(run(t, "r1"));
        assertTrue(store().claim(t, "r1", "w1", T0, T0.plusSeconds(300)).isPresent());
        assertFalse(store().claim(t, "r1", "w2", T0.plusSeconds(10), T0.plusSeconds(310)).isPresent());
        assertTrue(store().due(T0.plusSeconds(301), 100).stream().anyMatch(r -> r.id().equals("r1")));
        Run c = store().claim(t, "r1", "w2", T0.plusSeconds(301), T0.plusSeconds(601)).orElseThrow();
        assertEquals(2, c.attempts());
        assertEquals("w2", c.leaseOwner());
    }

    @Test
    public void finalAndWaitingRunsCannotBeClaimed() {
        String t = tenant();
        Run r = store().createRun(run(t, "r1"));
        store().saveRun(r.finished(RunStatus.DONE, null, T0));
        assertFalse(store().claim(t, "r1", "w1", T0, T0.plusSeconds(60)).isPresent());
        store().createRun(run(t, "r2"));
        store().saveRun(run(t, "r2").with(RunStatus.WAITING_APPROVAL, T0));
        assertFalse(store().claim(t, "r2", "w1", T0, T0.plusSeconds(60)).isPresent());
    }

    @Test
    public void aRetryIsDueAtItsTimeAndNotBefore() {
        String t = tenant();
        Run r = store().createRun(run(t, "r1"));
        store().saveRun(r.retryAt(T0.plusSeconds(60), "429", T0));
        assertFalse(store().claim(t, "r1", "w1", T0.plusSeconds(30), T0.plusSeconds(330)).isPresent());
        assertTrue(store().claim(t, "r1", "w1", T0.plusSeconds(60), T0.plusSeconds(360)).isPresent());
    }

    @Test
    public void stepsAreKeptOncePerName() {
        String runId = "r-" + UUID.randomUUID();
        store().putStep(new StepRecord(runId, 1, "reading", "{\"a\":1}", T0));
        store().putStep(new StepRecord(runId, 2, "reading", "{\"a\":2}", T0));
        assertEquals(1, store().steps(runId).size());
        assertEquals(1, Json.MAPPER.readTree(store().step(runId, "reading").orElseThrow().json()).get("a").asInt());
    }

    @Test
    public void anEffectIsBegunOnceAndCompleted() {
        String t = tenant();
        EffectRecord e = new EffectRecord(t, "invoice:C1:2026-10", "r1", "erp", EffectRecord.State.STARTED, T0);
        assertTrue(store().beginEffect(e));
        assertFalse(store().beginEffect(e));
        store().completeEffect(t, e.key(), T0);
        assertEquals(EffectRecord.State.DONE, store().effect(t, e.key()).orElseThrow().state());
    }

    @Test
    public void approvalsAreFoundByRunAndListedWhilePending() {
        String t = tenant();
        String runId = "r-" + UUID.randomUUID();
        Approval a = new Approval("a-" + UUID.randomUUID(), t, runId, "billing", "invoice", "large", "{\"x\":1}",
                Approval.Status.PENDING, null, T0, null);
        store().saveApproval(a);
        assertEquals(1, store().pendingApprovals(t).size());
        assertEquals(a.id(), store().approvalFor(runId, "invoice").orElseThrow().id());
        store().saveApproval(a.decided(true, "office", T0));
        assertEquals(0, store().pendingApprovals(t).size());
        assertEquals(Approval.Status.APPROVED, store().approval(t, a.id()).orElseThrow().status());
        assertTrue(store().approval(tenant(), a.id()).isEmpty(), "another tenant cannot see it");
    }

    @Test
    public void publishingAVersionRetiresThePreviousOne() {
        String t = tenant();
        store().saveVersion(new AgentVersion(t, "triage", 1, Map.of("minNeighbours", 3),
                AgentVersion.Status.PUBLISHED, "a", T0, null));
        store().saveVersion(new AgentVersion(t, "triage", 2, Map.of("minNeighbours", 5), AgentVersion.Status.DRAFT,
                "a", T0, null));
        assertEquals(1, store().published(t, "triage").orElseThrow().version());
        store().saveVersion(store().versions(t, "triage").get(1).with(AgentVersion.Status.PUBLISHED));
        assertEquals(2, store().published(t, "triage").orElseThrow().version());
        assertEquals(AgentVersion.Status.RETIRED, store().versions(t, "triage").get(0).status());
        assertEquals(5, ((Number) store().published(t, "triage").orElseThrow().config().get("minNeighbours")).intValue());
    }

    @Test
    public void spansAreListedPerRun() {
        String t = tenant();
        store().addSpan(new Span("tr", "s1", null, t, "r1", "run triage", T0, T0.plusSeconds(1),
                Map.of("gen_ai.usage.input_tokens", "12"), false));
        assertEquals("12", store().spans(t, "r1").get(0).attributes().get("gen_ai.usage.input_tokens"));
        assertTrue(store().spans(tenant(), "r1").isEmpty());
    }
}
