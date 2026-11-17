package mspagent.service.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mspagent.core.engine.AgentVersion;
import mspagent.core.engine.Approval;
import mspagent.core.engine.EffectRecord;
import mspagent.core.engine.Json;
import mspagent.core.engine.Run;
import mspagent.core.engine.RunStatus;
import mspagent.core.engine.RuntimeStore;
import mspagent.core.engine.StepRecord;
import mspagent.core.event.Event;
import mspagent.core.trace.Span;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/** The runtime store on PostgreSQL. Runs the same contract tests as the in-memory store. */
@Component
public class PostgresRuntimeStore implements RuntimeStore {
    private static final TypeReference<Map<String, String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECTS = new TypeReference<>() {};
    private static final String RUN_COLS = "tenant, id, agent_id, agent_version, event, status, attempts, "
            + "next_attempt_at, lease_owner, lease_until, created_at, updated_at, error, dry_run";

    private final JdbcClient jdbc;

    public PostgresRuntimeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static Timestamp ts(Instant t) {
        return t == null ? null : Timestamp.from(t);
    }

    static Instant at(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    static Run run(ResultSet rs, int n) throws SQLException {
        return new Run(rs.getString("id"), rs.getString("tenant"), rs.getString("agent_id"), rs.getInt("agent_version"),
                Json.read(rs.getString("event"), Event.class), RunStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"), at(rs, "next_attempt_at"), rs.getString("lease_owner"), at(rs, "lease_until"),
                at(rs, "created_at"), at(rs, "updated_at"), rs.getString("error"), rs.getBoolean("dry_run"));
    }

    @Override
    public boolean firstDelivery(String tenant, String source, String eventId, Instant at) {
        return jdbc.sql("""
                insert into deliveries (tenant, source, event_id, first_at) values (:t, :s, :e, :at)
                on conflict (tenant, source, event_id) do update set count = deliveries.count + 1
                returning (xmax = 0) as inserted""")
                .param("t", tenant).param("s", source).param("e", eventId).param("at", ts(at))
                .query(Boolean.class).single();
    }

    @Override
    public Run createRun(Run r) {
        jdbc.sql("insert into runs (" + RUN_COLS + ") values (:t, :id, :a, :v, cast(:e as jsonb), :s, :n, :na, :lo, "
                        + ":lu, :c, :u, :err, :dry) on conflict (tenant, id) do nothing")
                .params(runParams(r)).update();
        return run(r.tenant(), r.id()).orElseThrow();
    }

    private static Map<String, Object> runParams(Run r) {
        Map<String, Object> p = new HashMap<>();
        p.put("t", r.tenant());
        p.put("id", r.id());
        p.put("a", r.agentId());
        p.put("v", r.agentVersion());
        p.put("e", Json.write(r.event()));
        p.put("s", r.status().name());
        p.put("n", r.attempts());
        p.put("na", ts(r.nextAttemptAt()));
        p.put("lo", r.leaseOwner());
        p.put("lu", ts(r.leaseUntil()));
        p.put("c", ts(r.createdAt()));
        p.put("u", ts(r.updatedAt()));
        p.put("err", r.error());
        p.put("dry", r.dryRun());
        return p;
    }

    @Override
    public Optional<Run> run(String tenant, String runId) {
        return jdbc.sql("select " + RUN_COLS + " from runs where tenant = :t and id = :id")
                .param("t", tenant).param("id", runId).query(PostgresRuntimeStore::run).optional();
    }

    @Override
    public Optional<Run> claim(String tenant, String runId, String worker, Instant now, Instant until) {
        return jdbc.sql("""
                update runs set status = 'RUNNING', attempts = attempts + 1, lease_owner = :w, lease_until = :until,
                       next_attempt_at = null, updated_at = :now
                 where tenant = :t and id = :id
                   and status not in ('DONE', 'FAILED', 'DEAD', 'WAITING_APPROVAL')
                   and not (status = 'RUNNING' and lease_until > :now and lease_owner <> :w)
                   and not (status = 'RETRY' and next_attempt_at > :now)
                """ + "returning " + RUN_COLS)
                .param("t", tenant).param("id", runId).param("w", worker).param("until", ts(until))
                .param("now", ts(now))
                .query(PostgresRuntimeStore::run).optional();
    }

    @Override
    public void saveRun(Run r) {
        jdbc.sql("""
                update runs set agent_version = :v, status = :s, attempts = :n, next_attempt_at = :na,
                       lease_owner = :lo, lease_until = :lu, updated_at = :u, error = :err
                 where tenant = :t and id = :id""")
                .params(runParams(r)).update();
    }

    @Override
    public List<Run> due(Instant now, int limit) {
        return jdbc.sql("select " + RUN_COLS + " from runs where (status = 'RETRY' and next_attempt_at <= :now) "
                        + "or (status = 'RUNNING' and lease_until <= :now) or status = 'PENDING' "
                        + "order by updated_at limit :n")
                .param("now", ts(now)).param("n", limit).query(PostgresRuntimeStore::run).list();
    }

    @Override
    public List<Run> runs(String tenant, RunQuery q) {
        return jdbc.sql("select " + RUN_COLS + " from runs where tenant = :t "
                        + "and (cast(:a as text) is null or agent_id = :a) and (cast(:s as text) is null or status = :s) "
                        + "and dry_run = false order by created_at desc limit :n")
                .param("t", tenant).param("a", q.agentId()).param("s", q.status() == null ? null : q.status().name())
                .param("n", q.limit())
                .query(PostgresRuntimeStore::run).list();
    }

    @Override
    public Optional<StepRecord> step(String runId, String name) {
        return jdbc.sql("select run_id, seq, name, json::text as json, at from steps where run_id = :r and name = :n")
                .param("r", runId).param("n", name).query(PostgresRuntimeStore::step).optional();
    }

    private static StepRecord step(ResultSet rs, int n) throws SQLException {
        return new StepRecord(rs.getString("run_id"), rs.getInt("seq"), rs.getString("name"), rs.getString("json"),
                at(rs, "at"));
    }

    @Override
    public void putStep(StepRecord s) {
        jdbc.sql("insert into steps (run_id, seq, name, json, at) values (:r, :q, :n, cast(:j as jsonb), :at) "
                        + "on conflict (run_id, name) do nothing")
                .param("r", s.runId()).param("q", s.seq()).param("n", s.name()).param("j", s.json())
                .param("at", ts(s.at())).update();
    }

    @Override
    public List<StepRecord> steps(String runId) {
        return jdbc.sql("select run_id, seq, name, json::text as json, at from steps where run_id = :r order by seq")
                .param("r", runId).query(PostgresRuntimeStore::step).list();
    }

    @Override
    public Optional<EffectRecord> effect(String tenant, String key) {
        return jdbc.sql("select tenant, key, run_id, tool, state, at from effects where tenant = :t and key = :k")
                .param("t", tenant).param("k", key)
                .query((rs, n) -> new EffectRecord(rs.getString("tenant"), rs.getString("key"), rs.getString("run_id"),
                        rs.getString("tool"), EffectRecord.State.valueOf(rs.getString("state")), at(rs, "at")))
                .optional();
    }

    @Override
    public boolean beginEffect(EffectRecord e) {
        return jdbc.sql("insert into effects (tenant, key, run_id, tool, state, at) values (:t, :k, :r, :tool, :s, :at) "
                        + "on conflict (tenant, key) do nothing")
                .param("t", e.tenant()).param("k", e.key()).param("r", e.runId()).param("tool", e.tool())
                .param("s", e.state().name()).param("at", ts(e.at())).update() == 1;
    }

    @Override
    public void completeEffect(String tenant, String key, Instant at) {
        jdbc.sql("update effects set state = 'DONE', at = :at where tenant = :t and key = :k")
                .param("t", tenant).param("k", key).param("at", ts(at)).update();
    }

    private static Approval approval(ResultSet rs, int n) throws SQLException {
        return new Approval(rs.getString("id"), rs.getString("tenant"), rs.getString("run_id"),
                rs.getString("agent_id"), rs.getString("name"), rs.getString("reason"), rs.getString("payload"),
                Approval.Status.valueOf(rs.getString("status")), rs.getString("decided_by"), at(rs, "created_at"),
                at(rs, "decided_at"));
    }

    private static final String APPROVAL_COLS =
            "id, tenant, run_id, agent_id, name, reason, payload::text as payload, status, decided_by, created_at, decided_at";

    @Override
    public Optional<Approval> approval(String tenant, String id) {
        return jdbc.sql("select " + APPROVAL_COLS + " from approvals where tenant = :t and id = :id")
                .param("t", tenant).param("id", id).query(PostgresRuntimeStore::approval).optional();
    }

    @Override
    public Optional<Approval> approvalFor(String runId, String name) {
        return jdbc.sql("select " + APPROVAL_COLS + " from approvals where run_id = :r and name = :n")
                .param("r", runId).param("n", name).query(PostgresRuntimeStore::approval).optional();
    }

    @Override
    public void saveApproval(Approval a) {
        jdbc.sql("""
                insert into approvals (id, tenant, run_id, agent_id, name, reason, payload, status, decided_by,
                                       created_at, decided_at)
                values (:id, :t, :r, :a, :n, :reason, cast(:p as jsonb), :s, :by, :c, :d)
                on conflict (id) do update set status = :s, decided_by = :by, decided_at = :d""")
                .param("id", a.id()).param("t", a.tenant()).param("r", a.runId()).param("a", a.agentId())
                .param("n", a.name()).param("reason", a.reason()).param("p", a.payloadJson())
                .param("s", a.status().name()).param("by", a.decidedBy()).param("c", ts(a.createdAt()))
                .param("d", ts(a.decidedAt())).update();
    }

    @Override
    public List<Approval> pendingApprovals(String tenant) {
        return jdbc.sql("select " + APPROVAL_COLS + " from approvals where tenant = :t and status = 'PENDING' "
                        + "order by created_at")
                .param("t", tenant).query(PostgresRuntimeStore::approval).list();
    }

    @Override
    public void addSpan(Span s) {
        jdbc.sql("""
                insert into spans (tenant, run_id, trace_id, span_id, parent_id, name, start_at, end_at, attributes, error)
                values (:t, :r, :tr, :sp, :p, :n, :s, :e, cast(:a as jsonb), :err)""")
                .param("t", s.tenant()).param("r", s.runId()).param("tr", s.traceId()).param("sp", s.spanId())
                .param("p", s.parentId()).param("n", s.name()).param("s", ts(s.start())).param("e", ts(s.end()))
                .param("a", Json.write(s.attributes())).param("err", s.error()).update();
    }

    static Span span(ResultSet rs, int n) throws SQLException {
        return new Span(rs.getString("trace_id"), rs.getString("span_id"), rs.getString("parent_id"),
                rs.getString("tenant"), rs.getString("run_id"), rs.getString("name"), at(rs, "start_at"),
                at(rs, "end_at"), Json.MAPPER.readValue(rs.getString("attributes"), STRINGS), rs.getBoolean("error"));
    }

    static final String SPAN_COLS =
            "tenant, run_id, trace_id, span_id, parent_id, name, start_at, end_at, attributes::text as attributes, error";

    @Override
    public List<Span> spans(String tenant, String runId) {
        return jdbc.sql("select " + SPAN_COLS + " from spans where tenant = :t and run_id = :r order by id")
                .param("t", tenant).param("r", runId).query(PostgresRuntimeStore::span).list();
    }

    private static AgentVersion version(ResultSet rs, int n) throws SQLException {
        return new AgentVersion(rs.getString("tenant"), rs.getString("agent_id"), rs.getInt("version"),
                Json.MAPPER.readValue(rs.getString("config"), OBJECTS), AgentVersion.Status.valueOf(rs.getString("status")),
                rs.getString("created_by"), at(rs, "created_at"), rs.getString("note"));
    }

    private static final String VERSION_COLS =
            "tenant, agent_id, version, config::text as config, status, created_by, created_at, note";

    @Override
    public Optional<AgentVersion> published(String tenant, String agentId) {
        return jdbc.sql("select " + VERSION_COLS + " from agent_versions where tenant = :t and agent_id = :a "
                        + "and status = 'PUBLISHED'")
                .param("t", tenant).param("a", agentId).query(PostgresRuntimeStore::version).optional();
    }

    @Override
    public List<AgentVersion> versions(String tenant, String agentId) {
        return jdbc.sql("select " + VERSION_COLS + " from agent_versions where tenant = :t and agent_id = :a "
                        + "order by version")
                .param("t", tenant).param("a", agentId).query(PostgresRuntimeStore::version).list();
    }

    @Override
    @Transactional
    public void saveVersion(AgentVersion v) {
        if (v.status() == AgentVersion.Status.PUBLISHED) {
            jdbc.sql("update agent_versions set status = 'RETIRED' where tenant = :t and agent_id = :a "
                            + "and status = 'PUBLISHED' and version <> :v")
                    .param("t", v.tenant()).param("a", v.agentId()).param("v", v.version()).update();
        }
        jdbc.sql("""
                insert into agent_versions (tenant, agent_id, version, config, status, created_by, created_at, note)
                values (:t, :a, :v, cast(:c as jsonb), :s, :by, :at, :note)
                on conflict (tenant, agent_id, version) do update set status = :s, config = cast(:c as jsonb), note = :note""")
                .param("t", v.tenant()).param("a", v.agentId()).param("v", v.version()).param("c", Json.write(v.config()))
                .param("s", v.status().name()).param("by", v.createdBy()).param("at", ts(v.createdAt()))
                .param("note", v.note()).update();
    }
}
