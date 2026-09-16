package haven.loom.domain;

/**
 * The verdicts a reviewer (human or agent) reaches on a case.
 *
 * `resultingState` is the STATE_BY_ACTION mapping, held on the action itself so
 * the pairing cannot drift. DISPUTE is not terminal: loom has told Anvil it
 * disagrees, and the case stays open until someone acts or the SLA runs out.
 */
public enum ReviewAction {
    AGREE("agree", CaseState.AGREED, true),
    DISPUTE("dispute", CaseState.DISPUTED, false),
    ESCALATE("escalate", CaseState.ESCALATED, true);

    private final String wire;
    private final CaseState resultingState;
    private final boolean terminal;

    ReviewAction(String wire, CaseState resultingState, boolean terminal) {
        this.wire = wire;
        this.resultingState = resultingState;
        this.terminal = terminal;
    }

    public static ReviewAction fromWire(String wire) {
        for (ReviewAction action : values()) {
            if (action.wire.equals(wire)) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown review action: " + wire);
    }

    public CaseState resultingState() {
        return resultingState;
    }

    public boolean terminal() {
        return terminal;
    }

    @Override
    public String toString() {
        return wire;
    }
}
