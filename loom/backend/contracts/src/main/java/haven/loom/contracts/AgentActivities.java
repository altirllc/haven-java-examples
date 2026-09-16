package haven.loom.contracts;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The autonomous agent's sweep, as an INTERFACE only.
 *
 * The agent must run in an activity, never in workflow code: it reads config,
 * calls the model gateway, and touches Postgres — all of which would break replay.
 */
@ActivityInterface
public interface AgentActivities {

    /** Review the open cases nearest their deadline. Returns how many were swept. */
    @ActivityMethod
    int reviewOpenCases();
}
