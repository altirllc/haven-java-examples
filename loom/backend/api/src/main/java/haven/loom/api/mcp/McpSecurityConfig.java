package haven.loom.api.mcp;

import haven.loom.api.HavenProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpStatus;

/**
 * MCP authentication — zero-trust, self-validated.
 *
 * MCP requests carry a Zitadel Bearer JWT the tenant's user obtained via
 * Authorization Code + PKCE against the per-tenant MCP client (e.g. from Claude
 * Code). We verify it here rather than trusting an upstream: the resource-server
 * model the MCP spec prescribes, and the same posture system/api takes. A
 * request passes only if all three hold:
 *   1. the JWT verifies (signature + issuer) against the Zitadel JWKS,
 *   2. its org (resource owner) equals this tenant, and
 *   3. its audience contains the per-tenant MCP client id.
 *
 * Fails CLOSED: if the issuer or audience is unconfigured, every request is
 * denied rather than the surface opening up.
 */
@Configuration
public class McpSecurityConfig {

    private static final String RESOURCE_OWNER_NAME_CLAIM = "urn:zitadel:iam:user:resourceowner:name";

    @Bean
    SecurityFilterChain mcpFilterChain(HttpSecurity http, McpProperties mcp) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS));

        if (!mcp.configured()) {
            // Deny closed. Nothing is reachable without MCP identity configured —
            // but still advertise how to authenticate, so a misconfigured tenant
            // looks like "needs auth" to a client rather than a broken endpoint.
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(e -> e.authenticationEntryPoint(new McpAuthenticationEntryPoint()))
                    .build();
        }

        return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.authenticationEntryPoint(new McpAuthenticationEntryPoint())
                        .jwt(jwt -> {}))
                .build();
    }

    @Bean
    JwtDecoder mcpJwtDecoder(McpProperties mcp, HavenProperties haven) {
        if (!mcp.configured()) {
            // No JWKS to fetch; the chain above denies everything anyway.
            return token -> {
                throw new org.springframework.security.oauth2.jwt.BadJwtException("MCP auth is not configured");
            };
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(mcp.jwksUri()).build();
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                List.of(
                        JwtValidators.createDefaultWithIssuer(mcp.issuer()),
                        orgIs(haven.tenantId()),
                        audienceContains(mcp.mcpAudience()))));
        return decoder;
    }

    /** The token's org must be THIS tenant — another tenant's valid token is not valid here. */
    static OAuth2TokenValidator<Jwt> orgIs(String tenantId) {
        return jwt -> tenantId.equals(jwt.getClaimAsString(RESOURCE_OWNER_NAME_CLAIM))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "org is not this tenant", null));
    }

    static OAuth2TokenValidator<Jwt> audienceContains(String audience) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "audience is not the tenant MCP client", null));
    }

    /**
     * A 401 must advertise where to authenticate (RFC 9728). The metadata document
     * itself is served by the platform edge at the canonical host-root location,
     * not by this app — the app's only discovery job is this pointer.
     */
    static final class McpAuthenticationEntryPoint
            implements org.springframework.security.web.AuthenticationEntryPoint {

        @Override
        public void commence(
                HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response,
                org.springframework.security.core.AuthenticationException failed)
                throws java.io.IOException {
            response.setHeader(
                    "WWW-Authenticate", "Bearer resource_metadata=\"" + resourceMetadataUrl(request) + "\"");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}}");
        }

        /**
         * Built from the Traefik-forwarded host and the /mcp/{app} prefix that the
         * strip middleware removed. HTTPS always — tenant domains are TLS
         * externally, even though the inner entrypoint reports http.
         */
        private static String resourceMetadataUrl(HttpServletRequest request) {
            String host = request.getHeader("X-Forwarded-Host");
            if (host == null || host.isBlank()) {
                host = request.getHeader("Host") == null ? "" : request.getHeader("Host");
            }
            String prefix = request.getHeader("X-Forwarded-Prefix");
            return "https://" + host + "/.well-known/oauth-protected-resource" + (prefix == null ? "" : prefix);
        }
    }
}
