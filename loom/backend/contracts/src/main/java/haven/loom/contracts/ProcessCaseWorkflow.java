package haven.loom.contracts;

import haven.loom.domain.ReviewSignal;
import java.time.Duration;
import java.util.UUID;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One durable workflow per case — `loom-case-{id}`.
 *
 * It does NOT care who reviews. A human (REST route) and the autonomous agent
 * (via review-case) send the SAME signal; the agent is an automated stand-in for
 * the human supervisor. Neither writes case state directly.
 *
 * It also owns the SLA. That is the point of putting a workflow behind every
 * case rather than sweeping a table: the deadline survives restarts, deploys and
 * a loom that is down for a day, and it fires exactly once.
 *
 * The interface lives in :contracts because both sides need it: :workflows
 * implements it, while :api and :agent hold typed stubs to signal it.
 */
@WorkflowInterface
public interface ProcessCaseWorkflow {

    String WORKFLOW_ID_PREFIX = "loom-case-";

    static String workflowId(UUID caseId) {
        return WORKFLOW_ID_PREFIX + caseId;
    }

    @WorkflowMethod
    void processCase(UUID caseId, Duration sla);

    @SignalMethod
    void review(ReviewSignal signal);
}
