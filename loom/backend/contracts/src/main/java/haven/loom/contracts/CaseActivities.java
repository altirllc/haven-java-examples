package haven.loom.contracts;

import haven.loom.domain.Channel;
import haven.loom.domain.ReviewSignal;
import java.util.Map;
import java.util.UUID;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The case writes a workflow may perform — as an INTERFACE only.
 *
 * :workflows compiles against this and holds a stub; the implementation lives in
 * :jobs and is the only side that sees the cases service. That split is what
 * keeps IO out of workflow code.
 */
@ActivityInterface
public interface CaseActivities {

    /**
     * Apply a verdict. The signal carries the action, and the action carries the
     * state it results in, so the workflow never has to restate that mapping.
     */
    @ActivityMethod
    void applyReview(ReviewSignal signal);

    /**
     * Ask Anvil to look again at the item behind this case.
     *
     * Called only after a DISPUTE. Writes its own audit row, so the case
     * timeline records what Anvil said — including "it had already resolved it",
     * which is an outcome rather than an error.
     */
    @ActivityMethod
    void pushBackToAnvil(UUID caseId);

    /**
     * Tell a human this case needs them.
     *
     * Called when loom escalates and when a case lapses — the two moments a
     * person has to know about. Writes its own audit row either way, including
     * when the tenant has not configured notifications: silence is the one
     * outcome a supervisor must never produce.
     *
     * @param reason why, in loom's own vocabulary: `escalated` or `lapsed`
     */
    @ActivityMethod
    void notifyHumans(UUID caseId, String reason);

    /** The SLA ran out with nobody reviewing. */
    @ActivityMethod
    void markLapsed(UUID caseId);

    @ActivityMethod
    void logActivity(UUID caseId, String action, String actor, Channel channel, String message, Map<String, Object> details);
}
