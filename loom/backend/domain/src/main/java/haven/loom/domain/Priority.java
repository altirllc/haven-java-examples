package haven.loom.domain;

/** Urgency carried by a review, and mirrored from Anvil's own decision. */
public enum Priority {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    private final String wire;

    Priority(String wire) {
        this.wire = wire;
    }

    public static Priority fromWire(String wire) {
        for (Priority p : values()) {
            if (p.wire.equals(wire)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown priority: " + wire);
    }

    @Override
    public String toString() {
        return wire;
    }
}
