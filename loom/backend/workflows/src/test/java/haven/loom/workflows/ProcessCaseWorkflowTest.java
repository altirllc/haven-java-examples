package haven.loom.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.withSettings;

import haven.loom.contracts.CaseActivities;
import haven.loom.contracts.ProcessCaseWorkflow;
import haven.loom.domain.Channel;
import haven.loom.domain.Priority;
import haven.loom.domain.ReviewAction;
import haven.loom.domain.ReviewSignal;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The case lifecycle, driven through a real (in-memory) Temporal server.
 *
 * The activities are mocked because this asserts the WORKFLOW's decisions — what
 * it applies and when it stops waiting — not what the database does. The
 * time-skipping environment lets a 24-hour SLA run in milliseconds.
 */
class ProcessCaseWorkflowTest {

    private static final String TASK_QUEUE = "test-loom";
    private static final Duration SLA = Duration.ofHours(24);

    private TestWorkflowEnvironment env;
    private CaseActivities activities;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        // withoutAnnotations: Mockito copies @ActivityMethod onto its proxy and
        // Temporal then rejects the registration as a duplicate definition.
        activities = mock(CaseActivities.class, withSettings().withoutAnnotations());

        Worker worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(ProcessCaseWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();

        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private ProcessCaseWorkflow start(UUID caseId) {
        ProcessCaseWorkflow workflow = client.newWorkflowStub(
                ProcessCaseWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(ProcessCaseWorkflow.workflowId(caseId))
                        .setTaskQueue(TASK_QUEUE)
                        .build());
        WorkflowClient.start(workflow::processCase, caseId, SLA);
        return workflow;
    }

    private static ReviewSignal signal(UUID caseId, ReviewAction action, Priority priority) {
        return new ReviewSignal(
                caseId, "dev@acme", Channel.API, action, priority, "because", "2026-01-01T00:00:00Z");
    }

    @Test
    void agreeIsTerminal() {
        UUID caseId = UUID.randomUUID();
        ProcessCaseWorkflow workflow = start(caseId);

        workflow.review(signal(caseId, ReviewAction.AGREE, Priority.LOW));

        verify(activities, timeout(5000)).applyReview(argThat(s -> s.action() == ReviewAction.AGREE));
        // The workflow returns rather than waiting out the SLA.
        env.sleep(Duration.ofHours(25));
        verify(activities, never()).markLapsed(caseId);
    }

    /**
     * The subtle one: a dispute is loom telling Anvil it disagrees, not a
     * conclusion. The case must stay open — a dispute that terminated would
     * silently stop watching exactly the work someone still has to resolve.
     */
    @Test
    void disputeIsNotTerminalAndTheCaseStillAcceptsAVerdict() {
        UUID caseId = UUID.randomUUID();
        ProcessCaseWorkflow workflow = start(caseId);

        workflow.review(signal(caseId, ReviewAction.DISPUTE, Priority.HIGH));
        verify(activities, timeout(5000)).applyReview(argThat(s -> s.action() == ReviewAction.DISPUTE));

        // Still listening: a human settles it afterwards.
        workflow.review(signal(caseId, ReviewAction.ESCALATE, Priority.HIGH));
        verify(activities, timeout(5000)).applyReview(argThat(s -> s.action() == ReviewAction.ESCALATE));
    }

    @Test
    void theCaseLapsesWhenTheSlaRunsOutWithNoVerdict() {
        UUID caseId = UUID.randomUUID();
        start(caseId);

        // Time-skipping: no real waiting.
        env.sleep(Duration.ofHours(25));

        verify(activities, timeout(5000)).markLapsed(caseId);
        verify(activities, timeout(5000))
                .logActivity(eq(caseId), eq("lapsed"), eq("system"), eq(Channel.SYSTEM), any(), any());
    }

    /**
     * The regression this file exists for. The deadline is fixed when the case
     * opens, so disputing does NOT buy another full SLA — otherwise an agent that
     * keeps disputing would keep a case alive forever and the lapse, which is the
     * whole point of supervising, would never fire.
     */
    @Test
    void disputingDoesNotRestartTheSlaClock() {
        UUID caseId = UUID.randomUUID();
        ProcessCaseWorkflow workflow = start(caseId);

        env.sleep(Duration.ofHours(20));
        workflow.review(signal(caseId, ReviewAction.DISPUTE, Priority.HIGH));
        verify(activities, timeout(5000)).applyReview(argThat(s -> s.action() == ReviewAction.DISPUTE));

        // Five more hours: past the ORIGINAL 24-hour deadline, but well short of
        // 24 hours from the dispute.
        env.sleep(Duration.ofHours(5));

        verify(activities, timeout(5000)).markLapsed(caseId);
    }

    @Test
    void aDisputeAsksAnvilToLookAgain() {
        // A dispute that Anvil never hears about is a diary entry, not
        // supervision.
        UUID caseId = UUID.randomUUID();
        ProcessCaseWorkflow workflow = start(caseId);

        workflow.review(signal(caseId, ReviewAction.DISPUTE, Priority.HIGH));

        verify(activities, timeout(5000)).pushBackToAnvil(caseId);
    }

    @Test
    void agreeingDoesNotTouchAnvil() {
        UUID caseId = UUID.randomUUID();
        ProcessCaseWorkflow workflow = start(caseId);

        workflow.review(signal(caseId, ReviewAction.AGREE, Priority.LOW));

        verify(activities, timeout(5000)).applyReview(argThat(s -> s.action() == ReviewAction.AGREE));
        verify(activities, never()).pushBackToAnvil(caseId);
    }

    @Test
    void anUnreachableAnvilLeavesTheDisputeStandingAndSaysSo() {
        // loom's verdict is its own and must survive Anvil being down. What the
        // timeline must not do is imply Anvil was told when it was not.
        UUID caseId = UUID.randomUUID();
        doThrow(new IllegalStateException("connection refused"))
                .when(activities)
                .pushBackToAnvil(caseId);
        ProcessCaseWorkflow workflow = start(caseId);

        workflow.review(signal(caseId, ReviewAction.DISPUTE, Priority.HIGH));

        verify(activities, timeout(5000))
                .logActivity(eq(caseId), eq("push-back-failed"), eq("system"), eq(Channel.SYSTEM), any(), any());
        // Still listening: the case stayed open, so a human can still settle it.
        workflow.review(signal(caseId, ReviewAction.ESCALATE, Priority.HIGH));
        verify(activities, timeout(5000)).applyReview(argThat(s -> s.action() == ReviewAction.ESCALATE));
    }

    @Test
    void escalatingTellsAHuman() {
        // An escalation nobody hears about is a state change, not an escalation.
        UUID caseId = UUID.randomUUID();
        ProcessCaseWorkflow workflow = start(caseId);

        workflow.review(signal(caseId, ReviewAction.ESCALATE, Priority.HIGH));

        verify(activities, timeout(5000)).notifyHumans(caseId, "escalated");
    }

    @Test
    void lapsingTellsAHuman() {
        // The most important notification loom sends: the deadline passed and
        // nobody looked - not Anvil, not loom, not a person. Nothing else will
        // ever raise it.
        UUID caseId = UUID.randomUUID();
        start(caseId);

        env.sleep(Duration.ofHours(25));

        verify(activities, timeout(5000)).notifyHumans(caseId, "lapsed");
    }

    @Test
    void agreeingTellsNobody() {
        UUID caseId = UUID.randomUUID();
        ProcessCaseWorkflow workflow = start(caseId);

        workflow.review(signal(caseId, ReviewAction.AGREE, Priority.LOW));

        verify(activities, timeout(5000)).applyReview(argThat(s -> s.action() == ReviewAction.AGREE));
        verify(activities, never()).notifyHumans(eq(caseId), any());
    }

    @Test
    void aFailedNotificationGoesInTheTimelineRatherThanKillingTheCase() {
        // A person who should have been told was not. That is news, and it
        // belongs where someone will see it.
        UUID caseId = UUID.randomUUID();
        doThrow(new IllegalStateException("broker unreachable"))
                .when(activities)
                .notifyHumans(caseId, "escalated");
        ProcessCaseWorkflow workflow = start(caseId);

        workflow.review(signal(caseId, ReviewAction.ESCALATE, Priority.HIGH));

        verify(activities, timeout(5000))
                .logActivity(eq(caseId), eq("notify-failed"), eq("system"), eq(Channel.SYSTEM), any(), any());
        verify(activities, timeout(5000)).applyReview(argThat(s -> s.action() == ReviewAction.ESCALATE));
    }

    @Test
    void theCaseLogsThatItIsWatchingBeforeAnyVerdict() {
        UUID caseId = UUID.randomUUID();
        start(caseId);

        verify(activities, timeout(5000))
                .logActivity(eq(caseId), eq("awaiting"), eq("system"), eq(Channel.SYSTEM), any(), any());
        assertThat(caseId).isNotNull();
    }
}
