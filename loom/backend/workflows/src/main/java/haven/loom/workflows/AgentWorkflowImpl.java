package haven.loom.workflows;

import haven.loom.contracts.AgentActivities;
import haven.loom.contracts.AgentWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * The daemon loop: sweep, sleep, repeat, then ContinueAsNew to keep history
 * bounded.
 *
 * SWEEPS_PER_EXECUTION at the 60s default is roughly 8 hours and a few thousand
 * history events per execution — comfortably under Temporal's soft limits while
 * keeping new executions rare.
 */
public class AgentWorkflowImpl implements AgentWorkflow {

    private static final int SWEEPS_PER_EXECUTION = 480;

    private final AgentActivities activities = Workflow.newActivityStub(
            AgentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    @Override
    public void run(Duration interval) {
        for (int i = 0; i < SWEEPS_PER_EXECUTION; i++) {
            try {
                activities.reviewOpenCases();
            } catch (CanceledFailure cancelled) {
                // Cancellation still wins.
                throw cancelled;
            } catch (RuntimeException failed) {
                // A failed sweep (after the activity's own retries) must not kill
                // the daemon. Skip it and sweep again next interval.
                Workflow.getLogger(AgentWorkflowImpl.class).warn("Sweep failed, continuing", failed);
            }
            Workflow.sleep(interval);
        }
        Workflow.continueAsNew(interval);
    }
}
