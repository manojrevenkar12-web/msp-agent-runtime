package mspagent.core.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mspagent.core.agent.Agent;
import mspagent.core.agent.ParamSpec;
import mspagent.core.engine.AgentVersion;
import mspagent.core.engine.Approval;
import mspagent.core.engine.Json;
import mspagent.core.engine.Run;
import mspagent.core.engine.StepRecord;
import mspagent.core.trace.Span;

/** The JSON shapes of the HTTP API and the console's demo data. One place, so both stay the same (docs/api.md). */
public final class Views {
    private Views() {}

    public static Map<String, Object> agent(Agent a, List<AgentVersion> versions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("title", a.title());
        m.put("description", a.description());
        m.put("eventTypes", a.eventTypes());
        AgentVersion pub = versions.stream().filter(v -> v.status() == AgentVersion.Status.PUBLISHED).findFirst()
                .orElse(null);
        List<Map<String, Object>> params = new ArrayList<>();
        for (ParamSpec p : a.params()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("type", p.type().name());
            pm.put("defaultValue", p.defaultValue());
            pm.put("min", p.min());
            pm.put("max", p.max());
            pm.put("label", p.label());
            Object v = pub == null ? null : pub.config().get(p.name());
            pm.put("value", v == null ? p.defaultValue() : v);
            params.add(pm);
        }
        m.put("params", params);
        m.put("published", pub == null ? null : pub.version());
        List<Map<String, Object>> vs = new ArrayList<>();
        for (AgentVersion v : versions) {
            Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("version", v.version());
            vm.put("status", v.status().name());
            vm.put("config", v.config());
            vm.put("createdBy", v.createdBy());
            vm.put("createdAt", v.createdAt().toString());
            vm.put("note", v.note());
            vs.add(vm);
        }
        m.put("versions", vs);
        return m;
    }

    public static Map<String, Object> runSummary(Run r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("agentId", r.agentId());
        m.put("agentVersion", r.agentVersion());
        m.put("status", r.status().name());
        m.put("attempts", r.attempts());
        m.put("eventType", r.event().type());
        m.put("eventId", r.event().id());
        m.put("subject", r.event().data().getOrDefault("ticketId", r.event().data().getOrDefault("customerId", "")));
        m.put("occurredAt", r.event().occurredAt().toString());
        m.put("createdAt", r.createdAt().toString());
        m.put("updatedAt", r.updatedAt().toString());
        m.put("nextAttemptAt", r.nextAttemptAt() == null ? null : r.nextAttemptAt().toString());
        m.put("error", r.error());
        return m;
    }

    public static Map<String, Object> runDetail(Run r, List<StepRecord> steps, List<Span> spans) {
        Map<String, Object> m = runSummary(r);
        List<Map<String, Object>> st = new ArrayList<>();
        for (StepRecord s : steps) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("seq", s.seq());
            sm.put("name", s.name());
            sm.put("at", s.at().toString());
            String json = s.json();
            sm.put("json", json.length() > 3000 ? json.substring(0, 3000) + "…" : json);
            st.add(sm);
        }
        m.put("steps", st);
        List<Map<String, Object>> sp = new ArrayList<>();
        for (Span s : spans) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("spanId", s.spanId());
            sm.put("parentId", s.parentId());
            sm.put("name", s.name());
            sm.put("start", s.start().toString());
            sm.put("end", s.end().toString());
            sm.put("attributes", s.attributes());
            sm.put("error", s.error());
            sp.add(sm);
        }
        m.put("spans", sp);
        return m;
    }

    public static Map<String, Object> approval(Approval a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("runId", a.runId());
        m.put("agentId", a.agentId());
        m.put("name", a.name());
        m.put("reason", a.reason());
        m.put("payload", Json.MAPPER.readTree(a.payloadJson()));
        m.put("status", a.status().name());
        m.put("createdAt", a.createdAt().toString());
        return m;
    }
}
