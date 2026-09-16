package haven.plumb.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * The smallest workflow that proves work can actually run: it calls one activity
 * and returns what came back.
 *
 * Note what this is NOT. A real Haven Java app walls its workflow code off from
 * Spring, Hibernate and IO with a module graph, an enforcer rule and ArchUnit,
 * because Temporal replays workflow code and Java has no sandbox to make that
 * safe. plumb is one Maven module and cannot have that fence — which is fine
 * only because this workflow does nothing but delegate. If a second line ever
 * gets added here, it belongs in an activity. See CLAUDE.md.
 */
@WorkflowInterface
public interface PingWorkflow {

    /** Workflow ids are prefixed so they cannot collide in the tenant's shared namespace. */
    static String workflowId(String token) {
        return "plumb-ping-" + token;
    }

    @WorkflowMethod
    String ping(String token);
}
