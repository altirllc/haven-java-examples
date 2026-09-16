package haven.loom.workflows;

import haven.loom.contracts.IngestActivities;
import haven.loom.contracts.IngestWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * The ingest loop: poll, sleep, repeat, then ContinueAsNew to keep history
 * bounded.
 *
 * POLLS_PER_EXECUTION at the 60s default is roughly 8 hours per execution —
 * comfortably under Temporal's soft history limits while keeping new executions
 * rare. Same shape as the agent daemon; the numbers are the same because the
 * cadence is.
 */
public class IngestWorkflowImpl implements IngestWorkflow {

    private static final int POLLS_PER_EXECUTION = 480;

    private final IngestActivities activities = Workflow.newActivityStub(
            IngestActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(
                            RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    @Override
    public void run(Duration interval) {
        for (int poll = 0; poll < POLLS_PER_EXECUTION; poll++) {
            try {
                activities.ingestFromAnvil();
            } catch (CanceledFailure cancelled) {
                // Cancellation still wins.
                throw cancelled;
            } catch (RuntimeException failed) {
                // Anvil being down, slow, or refusing us must not kill the
                // daemon — it is the one dependency loom cannot control, and a
                // dead ingest loop would need a human to notice and restart it.
                // Skip this poll and try again next interval.
                Workflow.getLogger(IngestWorkflowImpl.class).warn("Ingest poll failed, continuing", failed);
            }
            Workflow.sleep(interval);
        }
        Workflow.continueAsNew(interval);
    }
}
