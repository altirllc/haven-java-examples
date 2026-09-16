package haven.loom.domain;

/**
 * HOW an action arrived (interface) — the app API, the MCP surface, the
 * autonomous agent daemon, or the platform itself. A closed set.
 *
 * Distinct from the actor (WHO acted), which is open: a human's own id, or the
 * non-human principals 'agent' and 'system'.
 */
public enum Channel {
    API("api"),
    MCP("mcp"),
    AGENT("agent"),
    SYSTEM("system");

    private final String wire;

    Channel(String wire) {
        this.wire = wire;
    }

    public static Channel fromWire(String wire) {
        for (Channel c : values()) {
            if (c.wire.equals(wire)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown channel: " + wire);
    }

    @Override
    public String toString() {
        return wire;
    }
}
