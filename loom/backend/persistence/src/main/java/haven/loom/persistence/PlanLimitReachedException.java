package haven.loom.persistence;

/**
 * The free allowance is spent and only a plan change lifts the refusal.
 * Existing cases are never touched and reads are never blocked — only newly
 * opened cases are refused. Rendered as HTTP 402, code PLAN_LIMIT_REACHED.
 */
public class PlanLimitReachedException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public PlanLimitReachedException(int allowance) {
        super("Free allowance of " + allowance + " cases reached");
    }
}
