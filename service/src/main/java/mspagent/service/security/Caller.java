package mspagent.service.security;

import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who is calling: a user of one tenant with one role. Every API query is scoped to {@link #tenant}. */
public record Caller(String tenant, String user, String role) {

    public static final List<String> ROLES = List.of("VIEWER", "OPERATOR", "ADMIN");

    /** The roles this caller holds: a role includes every role below it. */
    public List<String> roles() {
        return ROLES.subList(0, ROLES.indexOf(role) + 1);
    }

    public static Caller current() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (p instanceof Caller c) {
            return c;
        }
        throw new IllegalStateException("no caller");
    }
}
