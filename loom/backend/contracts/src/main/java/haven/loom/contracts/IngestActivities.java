package haven.loom.contracts;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Reading Anvil, as an INTERFACE only.
 *
 * Must be an activity, never workflow code: it does HTTP and touches Postgres,
 * neither of which survives replay. :workflows holds a stub; the implementation
 * lives in :jobs, which is the only side that knows Anvil exists at all.
 */
@ActivityInterface
public interface IngestActivities {

    /** Poll Anvil and open or refresh a case per item. Returns how many it touched. */
    @ActivityMethod
    int ingestFromAnvil();
}
