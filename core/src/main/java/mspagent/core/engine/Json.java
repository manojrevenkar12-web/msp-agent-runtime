package mspagent.core.engine;

import tools.jackson.databind.json.JsonMapper;

/** The one JSON mapper of the runtime: step results, approval payloads, effect plans. */
public final class Json {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {}

    public static String write(Object o) {
        return MAPPER.writeValueAsString(o);
    }

    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }
}
