package haven.loom.orchestration;

import haven.loom.contracts.ProcessCaseWorkflow;
import haven.loom.domain.Channel;
import haven.loom.domain.Priority;
import haven.loom.domain.ReviewAction;
import haven.loom.domain.ReviewSignal;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Orchestration for the case lifecycle: starting a case's durable workflow and
 * delivering verdicts to it.
 *
 * Deliberately NOT in the cases service, which owns persistence and the audit
 * log only. Kept here so every caller shares one definition of the signal: the
 * human REST routes, the MCP review tools, and the agent's review-case tool all
 * drive the identical path.
 */
@Component
public class CaseOrchestrator {

    private final WorkflowClient client;
    private final TemporalProperties properties;

    public CaseOrchestrator(WorkflowClient client, TemporalProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * Every open case runs a workflow — it is what holds the SLA. The deadline
     * lives in the workflow rather than in a sweep over the table so it survives
     * restarts and fires exactly once.
     */
    public void startProcessCase(UUID caseId, Duration sla) {
        ProcessCaseWorkflow workflow = client.newWorkflowStub(
                ProcessCaseWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(ProcessCaseWorkflow.workflowId(caseId))
                        .setTaskQueue(properties.taskQueue())
                        .build());
        WorkflowClient.start(workflow::processCase, caseId, sla);
    }

    /**
     * Deliver a verdict to the case's workflow. Returns false ONLY when the
     * workflow has already resolved or was never started — the case is no longer
     * open to review. Anything else (a Temporal outage) propagates, so callers
     * report an upstream failure rather than telling the user their case was
     * already settled.
     */
    public boolean signalReview(
            UUID caseId, ReviewAction action, Priority priority, String rationale, String actor, Channel channel) {
        try {
            ProcessCaseWorkflow workflow =
                    client.newWorkflowStub(ProcessCaseWorkflow.class, ProcessCaseWorkflow.workflowId(caseId));
            workflow.review(new ReviewSignal(
                    caseId, actor, channel, action, priority, rationale, Instant.now().toString()));
            return true;
        } catch (WorkflowNotFoundException notRunning) {
            return false;
        }
    }
}
