package haven.loom.jobs;

import haven.loom.contracts.AgentActivities;
import haven.loom.contracts.IngestActivities;
import haven.loom.contracts.IngestWorkflow;
import haven.loom.contracts.AgentWorkflow;
import haven.loom.contracts.CaseActivities;
import haven.loom.agent.AgentDaemonProperties;
import haven.loom.orchestration.TemporalProperties;
import haven.loom.workflows.AgentWorkflowImpl;
import haven.loom.workflows.IngestWorkflowImpl;
import haven.loom.workflows.ProcessCaseWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * The worker — and the ONE place workflow implementations meet activity
 * implementations.
 *
 * The asymmetry here is load-bearing. Workflows register as CLASSES because they
 * are stateful and Temporal constructs one per execution via a public no-arg
 * constructor — there is no injection point, so a workflow cannot be a Spring
 * bean even by accident. Activities register as INSTANCES because they are
 * stateless and thread-safe, so they are ordinary beans. That is why :workflows
 * never needs to see an implementation, and why the fence costs nothing.
 */
@Configuration
@EnableConfigurationProperties({AgentDaemonProperties.class, IngestProperties.class})
public class WorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkerConfig.class);

    private final WorkflowClient client;
    private final TemporalProperties temporal;
    private final AgentDaemonProperties daemon;
    private final IngestProperties ingest;

    public WorkerConfig(
            WorkflowClient client,
            TemporalProperties temporal,
            AgentDaemonProperties daemon,
            IngestProperties ingest) {
        this.client = client;
        this.temporal = temporal;
        this.daemon = daemon;
        this.ingest = ingest;
    }

    @Bean(destroyMethod = "shutdown")
    // Pollers must not start before the gateway probe passes — a failed boot
    // otherwise tears down mid-poll and buries the diagnosis in poller stacks.
    @org.springframework.context.annotation.DependsOn("modelsGatewayProbe")
    public WorkerFactory workerFactory(
            CaseActivities caseActivities,
            AgentActivities agentActivities,
            IngestActivities ingestActivities) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(temporal.taskQueue());

        worker.registerWorkflowImplementationTypes(
                ProcessCaseWorkflowImpl.class, AgentWorkflowImpl.class, IngestWorkflowImpl.class);
        worker.registerActivitiesImplementations(caseActivities, agentActivities, ingestActivities);

        factory.start();
        log.info("Worker started on task queue: {}", temporal.taskQueue());
        return factory;
    }

    /**
     * Start the long-lived daemons, once each. The workflow ids are fixed and
     * `{appname}-` prefixed so a worker restart re-attaches to the running
     * executions instead of starting second ones.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureDaemons() {
        ensureIngestDaemon();
        ensureAgentDaemon();
    }

    /** Polls Anvil. Separate from the agent so it keeps its own cadence. */
    private void ensureIngestDaemon() {
        IngestWorkflow workflow = client.newWorkflowStub(
                IngestWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(IngestWorkflow.WORKFLOW_ID)
                        .setTaskQueue(temporal.taskQueue())
                        .build());
        try {
            WorkflowClient.start(workflow::run, ingest.interval());
            log.info("Started ingest daemon (poll every {})", ingest.interval());
        } catch (WorkflowExecutionAlreadyStarted alreadyRunning) {
            log.info("Ingest daemon already running - leaving it in place.");
        }
    }

    private void ensureAgentDaemon() {
        AgentWorkflow workflow = client.newWorkflowStub(
                AgentWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(AgentWorkflow.WORKFLOW_ID)
                        .setTaskQueue(temporal.taskQueue())
                        .build());
        // Temporal races the app at boot like every backing service. Bounded
        // wait, then the real error.
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 15; attempt++) {
            try {
                WorkflowClient.start(workflow::run, daemon.interval());
                log.info("Started agent daemon (sweep every {})", daemon.display());
                return;
            } catch (WorkflowExecutionAlreadyStarted alreadyRunning) {
                log.info("Agent daemon already running — leaving it in place.");
                return;
            } catch (RuntimeException unavailable) {
                lastFailure = unavailable;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        throw lastFailure;
    }
}
