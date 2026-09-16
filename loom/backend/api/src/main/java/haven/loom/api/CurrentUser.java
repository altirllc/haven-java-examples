package haven.loom.api;

import haven.loom.domain.Role;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the caller and gates on a minimum role.
 *
 * Reads take viewer (the floor — any authenticated user), writes take member,
 * configuration takes admin. An unauthenticated caller is denied outright: the
 * viewer floor is only for an authenticated user who holds no grant.
 */
@Component
public class CurrentUser {

    private final IdentityResolver identity;

    public CurrentUser(IdentityResolver identity) {
        this.identity = identity;
    }

    public RequestUser of(HttpServletRequest request) {
        return identity.resolve(request);
    }

    public RequestUser require(HttpServletRequest request, Role min) {
        RequestUser user = identity.resolve(request);
        if (!user.meets(min)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "requires " + min + " role");
        }
        return user;
    }
}
