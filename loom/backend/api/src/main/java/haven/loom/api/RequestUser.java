package haven.loom.api;

import haven.loom.domain.Role;
import java.util.List;

/**
 * The caller, as resolved from the Haven edge's headers.
 *
 * `sub` empty means unauthenticated — the edge forwarded no identity. That is
 * distinct from an authenticated user holding no grant, who floors to viewer.
 */
public record RequestUser(String sub, String email, List<String> roles) {

    public static final RequestUser ANONYMOUS = new RequestUser("", "", List.of());

    public boolean authenticated() {
        return !sub.isBlank();
    }

    public boolean meets(Role min) {
        return authenticated() && Role.meets(roles, min);
    }
}
