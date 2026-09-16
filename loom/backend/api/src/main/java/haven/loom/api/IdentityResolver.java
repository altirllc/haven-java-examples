package haven.loom.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Request identity from the Haven edge.
 *
 * The identity edge authenticates the tenant user (Zitadel) and sets
 * X-Auth-Request-User (the stable Zitadel sub) and X-Auth-Request-Email on every
 * gated request before it reaches this app. We trust them because the tenant
 * gateway is this app's sole ingress and sets them authoritatively — a client
 * cannot spoof them.
 *
 * Locally there is no edge, so we fall back to a recognisable dev identity. In
 * production the edge ALWAYS sets these, so their absence must not mint a
 * fabricated user: that would mask an auth outage and forge the audit actor.
 */
@Component
public class IdentityResolver {

    private static final String HEADER_SUB = "X-Auth-Request-User";
    private static final String HEADER_EMAIL = "X-Auth-Request-Email";
    private static final String HEADER_ROLES = "X-Auth-Request-Roles";

    private final boolean production;
    private final RequestUser localDev;

    public IdentityResolver(Environment environment, HavenProperties haven) {
        this.production = Arrays.asList(environment.getActiveProfiles()).contains("production");
        this.localDev = new RequestUser(
                "local-dev", haven.user() + "@" + haven.tenantId(), List.of(haven.role().toString()));
    }

    public RequestUser resolve(HttpServletRequest request) {
        String sub = trimmed(request.getHeader(HEADER_SUB));
        String email = trimmed(request.getHeader(HEADER_EMAIL));
        List<String> roles = Arrays.stream(trimmed(request.getHeader(HEADER_ROLES)).split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .toList();

        if (!sub.isEmpty() || !email.isEmpty()) {
            return new RequestUser(sub.isEmpty() ? email : sub, email.isEmpty() ? sub : email, roles);
        }
        if (!production) {
            return localDev;
        }
        return RequestUser.ANONYMOUS;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
