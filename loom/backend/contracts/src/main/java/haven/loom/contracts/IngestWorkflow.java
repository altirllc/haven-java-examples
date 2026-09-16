package haven.loom.contracts;

import java.time.Duration;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * The ingest daemon — a SINGLE long-lived execution that polls Anvil on a timer
 * and ContinueAsNew's to bound its history.
 *
 * Separate from the agent daemon on purpose. They fail differently and run at
 * different speeds: ingest is a couple of HTTP calls and should be frequent, a
 * review sweep is a series of model calls and should not be. Folding them into
 * one loop would make ingest run at the agent's pace, so a case could sit
 * undiscovered for as long as the slowest sweep takes.
 */
@WorkflowInterface
public interface IngestWorkflow {

    String WORKFLOW_ID = "loom-ingest";

    @WorkflowMethod
    void run(Duration interval);
}
