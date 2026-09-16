package haven.plumb.temporal;

import haven.plumb.config.TemporalProperties;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Temporal connection and worker, created on first use and cached.
 *
 * Lazy is not an optimisation here, it is the fail-open rule. Every other Haven
 * app builds its Temporal client as a startup bean and dies if the frontend is
 * unreachable; plumb has to boot precisely so it can tell you the frontend is
 * unreachable. So nothing touches Temporal until the probe runs.
 *
 * The worker is cached rather than rebuilt per sweep because a worker is a
 * poller: creating one every minute would leave a trail of pollers on the task
 * queue. A failed connect discards the cache so the next sweep retries cleanly
 * instead of reusing half-built state.
 */
@Component
public class TemporalSeam {

    private static final Logger log = LoggerFactory.getLogger(TemporalSeam.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final TemporalProperties properties;
    private volatile Session session;

    public TemporalSeam(TemporalProperties properties) {
        this.properties = properties;
    }

    /** The live connection plus a started worker on this app's task queue. */
    public record Session(WorkflowServiceStubs stubs, WorkflowClient client, WorkerFactory factory) {}

    public synchronized Session connect() {
        if (session != null) {
            return session;
        }
        WorkflowServiceStubs stubs = null;
        try {
            // Connected, not lazy: the probe wants a definitive answer now, and
            // newServiceStubs would defer the failure to the first call and
            // report it wrapped in something less legible.
            stubs = WorkflowServiceStubs.newConnectedServiceStubs(
                    WorkflowServiceStubsOptions.newBuilder()
                            .setTarget(properties.address())
                            .build(),
                    CONNECT_TIMEOUT);
            WorkflowClient client = WorkflowClient.newInstance(
                    stubs,
                    WorkflowClientOptions.newBuilder()
                            .setNamespace(properties.namespace())
                            .build());
            WorkerFactory factory = WorkerFactory.newInstance(client);
            Worker worker = factory.newWorker(properties.taskQueue());
            worker.registerWorkflowImplementationTypes(PingWorkflowImpl.class);
            worker.registerActivitiesImplementations(new PingActivitiesImpl());
            factory.start();
            log.info("Temporal worker polling task queue '{}' in namespace '{}'",
                    properties.taskQueue(), properties.namespace());
            session = new Session(stubs, client, factory);
            return session;
        } catch (RuntimeException failed) {
            // Half-built state is worse than none: a stubs handle whose worker
            // never started would make the next sweep look connected.
            closeQuietly(stubs);
            throw failed;
        }
    }

    /** Drop the cached session so the next probe run reconnects from scratch. */
    public synchronized void discard() {
        Session current = session;
        session = null;
        if (current != null) {
            current.factory().shutdown();
            closeQuietly(current.stubs());
        }
    }

    @PreDestroy
    public void close() {
        discard();
    }

    private void closeQuietly(WorkflowServiceStubs stubs) {
        if (stubs == null) {
            return;
        }
        try {
            stubs.shutdown();
        } catch (RuntimeException ignored) {
            // Shutting down a connection that never came up is not news.
        }
    }
}
