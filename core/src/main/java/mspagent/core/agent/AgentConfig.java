package mspagent.core.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An agent's settings for one tenant, checked against the agent's {@link ParamSpec}s. */
public final class AgentConfig {
    private final Map<String, Long> values;

    private AgentConfig(Map<String, Long> values) {
        this.values = Map.copyOf(values);
    }

    public static AgentConfig defaults(List<ParamSpec> specs) {
        return of(specs, Map.of());
    }

    /** Builds a config; unknown names and values outside a spec's limits are refused. */
    public static AgentConfig of(List<ParamSpec> specs, Map<String, Object> raw) {
        List<String> errors = validate(specs, raw);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        Map<String, Long> v = new HashMap<>();
        for (ParamSpec s : specs) {
            Object o = raw.get(s.name());
            v.put(s.name(), o == null ? s.defaultValue() : toLong(o));
        }
        return new AgentConfig(v);
    }

    public static List<String> validate(List<ParamSpec> specs, Map<String, Object> raw) {
        List<String> errors = new ArrayList<>();
        Map<String, ParamSpec> byName = new HashMap<>();
        specs.forEach(s -> byName.put(s.name(), s));
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            ParamSpec s = byName.get(e.getKey());
            if (s == null) {
                errors.add("unknown setting " + e.getKey());
                continue;
            }
            Long v;
            try {
                v = toLong(e.getValue());
            } catch (IllegalArgumentException ex) {
                errors.add(e.getKey() + ": " + ex.getMessage());
                continue;
            }
            if (v < s.min() || v > s.max()) {
                errors.add(e.getKey() + " must be between " + s.min() + " and " + s.max());
            }
        }
        return errors;
    }

    private static long toLong(Object o) {
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (o instanceof Number n) {
            if (n.doubleValue() != Math.rint(n.doubleValue())) {
                throw new IllegalArgumentException("a whole number is needed");
            }
            return n.longValue();
        }
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("not a number: " + s);
            }
        }
        throw new IllegalArgumentException("unsupported value " + o);
    }

    public long get(String name) {
        Long v = values.get(name);
        if (v == null) {
            throw new IllegalArgumentException("no setting " + name);
        }
        return v;
    }

    public int integer(String name) {
        return Math.toIntExact(get(name));
    }

    public boolean flag(String name) {
        return get(name) != 0;
    }

    public Map<String, Long> asMap() {
        return values;
    }
}
