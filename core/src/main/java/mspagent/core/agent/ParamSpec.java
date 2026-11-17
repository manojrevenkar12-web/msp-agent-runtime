package mspagent.core.agent;

/**
 * A setting a service provider may change for an agent in the console. Limits keep a setting inside what the agent was
 * tested for.
 */
public record ParamSpec(String name, Type type, long defaultValue, long min, long max, String label) {

    public enum Type {
        INT,
        CENTS,
        BOOL
    }

    public static ParamSpec integer(String name, long def, long min, long max, String label) {
        return new ParamSpec(name, Type.INT, def, min, max, label);
    }

    public static ParamSpec cents(String name, long def, long min, long max, String label) {
        return new ParamSpec(name, Type.CENTS, def, min, max, label);
    }

    public static ParamSpec flag(String name, boolean def, String label) {
        return new ParamSpec(name, Type.BOOL, def ? 1 : 0, 0, 1, label);
    }
}
