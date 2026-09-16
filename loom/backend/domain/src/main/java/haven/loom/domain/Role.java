package haven.loom.domain;

import java.util.Collection;

/**
 * Access roles, mirrored as Zitadel project roles and forwarded by the edge as
 * `X-Auth-Request-Roles`. Ordered least to most privileged and NESTED — admin
 * implies member implies viewer.
 */
public enum Role {
    VIEWER("viewer"),
    MEMBER("member"),
    ADMIN("admin");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    public static Role fromWire(String wire) {
        for (Role r : values()) {
            if (r.wire.equals(wire)) {
                return r;
            }
        }
        throw new IllegalArgumentException("Unknown role: " + wire);
    }

    /**
     * Whether the held roles satisfy `min` under the nested hierarchy, with the
     * viewer floor: an authenticated user holding no recognised grant is a
     * viewer, so this holds for anyone authenticated. Unrecognised role strings
     * are ignored rather than rejected — the edge is free to forward grants this
     * app does not model.
     *
     * Callers must reject unauthenticated requests separately; the floor is only
     * for an authenticated user who simply holds no grant.
     */
    public static boolean meets(Collection<String> held, Role min) {
        int best = VIEWER.ordinal();
        for (String candidate : held) {
            for (Role role : values()) {
                if (role.wire.equals(candidate) && role.ordinal() > best) {
                    best = role.ordinal();
                }
            }
        }
        return best >= min.ordinal();
    }

    @Override
    public String toString() {
        return wire;
    }
}
