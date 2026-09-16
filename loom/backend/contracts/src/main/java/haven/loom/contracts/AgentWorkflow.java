package haven.loom.contracts;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;

/**
 * The agent daemon — a SINGLE long-lived execution that sweeps on a timer and
 * ContinueAsNew's to bound its history.
 *
 * Not a Schedule and not one execution per tick: a Schedule pays a full workflow
 * history plus a visibility row on EVERY tick. One looping execution collapses
 * that to a single execution that sleeps between sweeps.
 */
@WorkflowInterface
public interface AgentWorkflow {

    String WORKFLOW_ID = "loom-agent";

    @WorkflowMethod
    void run(Duration interval);
}
