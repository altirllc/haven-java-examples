package haven.loom.workflows;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import haven.loom.contracts.IngestActivities;
import haven.loom.contracts.IngestWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ingest daemon's one job beyond polling: staying alive.
 *
 * Anvil is the dependency loom cannot control. If a failed poll ended the
 * daemon, a five-minute Anvil restart would silently stop all supervision until
 * a human noticed and redeployed loom — the exact class of failure loom exists
 * to catch, happening to loom itself.
 */
class IngestWorkflowTest {

    private static final String TASK_QUEUE = "test-loom-ingest";
    private static final Duration INTERVAL = Duration.ofSeconds(30);

    private TestWorkflowEnvironment env;
    private IngestActivities activities;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        // withoutAnnotations: Mockito copies @ActivityMethod onto its proxy and
        // Temporal then rejects the registration as a duplicate definition.
        activities = mock(IngestActivities.class, withSettings().withoutAnnotations());

        Worker worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(IngestWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();

        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private void start() {
        IngestWorkflow workflow = client.newWorkflowStub(
                IngestWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(IngestWorkflow.WORKFLOW_ID)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
        WorkflowClient.start(workflow::run, INTERVAL);
    }

    @Test
    void pollsOnEveryInterval() {
        when(activities.ingestFromAnvil()).thenReturn(3);
        start();

        // Time-skipping: three intervals pass in milliseconds.
        env.sleep(Duration.ofSeconds(95));

        verify(activities, timeout(5000).atLeast(3)).ingestFromAnvil();
    }

    @Test
    void keepsPollingAfterAFailedPoll() {
        when(activities.ingestFromAnvil())
                .thenThrow(new IllegalStateException("Could not read Anvil: connection refused"))
                .thenReturn(1);
        start();

        env.sleep(Duration.ofSeconds(95));

        // More than one call means the failure did not end the loop. The
        // activity's own retries run first, so this is the daemon surviving
        // exhausted retries, not a single retry.
        verify(activities, timeout(5000).atLeast(2)).ingestFromAnvil();
    }

    @Test
    void survivesAnAnvilThatIsDownForEveryPoll() {
        when(activities.ingestFromAnvil()).thenThrow(new IllegalStateException("Could not read Anvil"));
        start();

        env.sleep(Duration.ofSeconds(185));

        verify(activities, timeout(5000).atLeast(3)).ingestFromAnvil();
    }
}
