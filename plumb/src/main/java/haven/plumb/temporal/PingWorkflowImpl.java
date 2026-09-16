package haven.plumb.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/** Delegates to the activity and returns its answer. Nothing else belongs here. */
public class PingWorkflowImpl implements PingWorkflow {

    // One attempt, short timeout. Temporal's defaults retry an activity for
    // effectively ever, which is right for real work and wrong for a probe: a
    // broken seam would be reported as "still running" instead of as broken.
    private final PingActivities activities = Workflow.newActivityStub(
            PingActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setRetryOptions(
                            RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

    @Override
    public String ping(String token) {
        return activities.echo(token);
    }
}
