package haven.loom.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The REST listener is gated by the Haven edge, not by Spring Security.
 *
 * The tenant gateway authenticates against Zitadel and forwards
 * X-Auth-Request-* on every request; it is this app's sole ingress, so a client
 * cannot spoof those headers. Authorization happens per-route against those
 * headers (see CurrentUser), which is why the filter chain itself is open.
 *
 * Spring Security is on the classpath only for the MCP listener, which DOES
 * self-validate. Without this bean its defaults would 401 the REST API.
 */
@Configuration
public class RestSecurityConfig {

    @Bean
    SecurityFilterChain restFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // No browser sessions and no cookies: every request carries its own
                // identity from the edge, so there is nothing for CSRF to protect.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .build();
    }
}
