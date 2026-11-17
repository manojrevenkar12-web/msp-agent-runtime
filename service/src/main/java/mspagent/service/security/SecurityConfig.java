package mspagent.service.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * API keys per tenant and role. Webhooks are verified by signature, the WebSocket by its handshake, health and the
 * console's static files are public; everything under /api needs a key.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ApiKeys keys) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyFilter(keys), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/hooks/**", "/ws/**", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/agents/*/versions").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/agents/*/versions/*/publish").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasRole("OPERATOR")
                        .requestMatchers("/api/**").hasRole("VIEWER")
                        .anyRequest().permitAll());
        return http.build();
    }
}
