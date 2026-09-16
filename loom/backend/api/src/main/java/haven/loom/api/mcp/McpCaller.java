package haven.loom.api.mcp;

import haven.loom.domain.Role;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * WHO is calling an MCP tool, read from the validated Zitadel token.
 *
 * Tool beans are singletons, so the caller cannot be closed over at
 * construction — it comes from the security context per request.
 */
@Component
public class McpCaller {

    private static final String PROJECT_ROLES_CLAIM = "urn:zitadel:iam:org:project:roles";

    /**
     * Assert the caller holds `min` and return their identity for the audit log.
     * Reads are open to any authenticated MCP user; writes require member.
     */
    public String require(Role min) {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            throw new IllegalStateException("Unauthenticated MCP call");
        }
        List<String> roles = rolesOf(jwt);
        if (!Role.meets(roles, min)) {
            throw new IllegalStateException("Forbidden: this action requires the " + min + " role.");
        }
        return actorOf(jwt);
    }

    private static Jwt currentJwt() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken token ? token.getToken() : null;
    }

    /**
     * Zitadel access tokens omit `email` unless the scope is granted, so
     * preferred_username is the human-readable fallback before the subject id.
     */
    private static String actorOf(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) {
            return email;
        }
        String username = jwt.getClaimAsString("preferred_username");
        if (username != null && !username.isBlank()) {
            return username;
        }
        return jwt.getSubject() == null ? "mcp-user" : jwt.getSubject();
    }

    private static List<String> rolesOf(Jwt jwt) {
        Map<String, Object> claim = jwt.getClaimAsMap(PROJECT_ROLES_CLAIM);
        return claim == null ? List.of() : List.copyOf(claim.keySet());
    }
}
