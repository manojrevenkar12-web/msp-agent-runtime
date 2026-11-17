package mspagent.service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reads {@code X-Api-Key} or {@code Authorization: Bearer} and authenticates the request as the key's caller. */
public class ApiKeyFilter extends OncePerRequestFilter {
    private final ApiKeys keys;

    public ApiKeyFilter(ApiKeys keys) {
        this.keys = keys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader("X-Api-Key");
        String auth = req.getHeader("Authorization");
        if (key == null && auth != null && auth.startsWith("Bearer ")) {
            key = auth.substring(7);
        }
        keys.find(key).ifPresent(c -> SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(c, null,
                        c.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList())));
        chain.doFilter(req, res);
    }
}
