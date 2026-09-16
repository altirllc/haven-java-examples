package haven.loom.domain;

/**
 * The states a case can hold (the DB `state` column). Single source of truth:
 * derive validation and mappings from this, never re-declare the set.
 *
 * A case is loom's supervision record for one Anvil item — not the item itself.
 * Anvil owns the item's status; loom owns its own verdict about it, which is why
 * these states describe a review and not a work item.
 */
public enum CaseState {
    /** Open: Anvil has decided (or not yet), and loom has not passed judgement. */
    WATCHING("watching"),
    /** loom reviewed and agrees with Anvil. Nothing more to do. */
    AGREED("agreed"),
    /** loom pushed back on Anvil and is still watching what happens next. */
    DISPUTED("disputed"),
    /** Raised to a human through Dispatch. loom stops watching. */
    ESCALATED("escalated"),
    /** The SLA ran out with no review at all — the failure loom exists to catch. */
    LAPSED("lapsed");

    private final String wire;

    CaseState(String wire) {
        this.wire = wire;
    }

    public static CaseState fromWire(String wire) {
        for (CaseState state : values()) {
            if (state.wire.equals(wire)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown case state: " + wire);
    }

    /**
     * Whether the case still awaits loom's verdict — its open half
     * (watching/disputed). A dispute stays open ON PURPOSE: loom has said it
     * disagrees and is waiting to see whether anyone acts, and the SLA clock is
     * still running against it. Everything else (agreed/escalated/lapsed) is
     * closed: no reopening, and no edits from any surface.
     */
    public boolean isOpen() {
        return this == WATCHING || this == DISPUTED;
    }

    /** The JSON/DB representation. Jackson emits this via WRITE_ENUMS_USING_TO_STRING. */
    @Override
    public String toString() {
        return wire;
    }
}
