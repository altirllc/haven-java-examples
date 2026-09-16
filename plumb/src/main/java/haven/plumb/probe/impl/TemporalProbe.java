package haven.plumb.probe.impl;

import haven.plumb.config.PlumbProperties;
import haven.plumb.config.TemporalProperties;
import haven.plumb.probe.Probe;
import haven.plumb.probe.ProbeGroup;
import haven.plumb.temporal.PingWorkflow;
import haven.plumb.temporal.TemporalSeam;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.DescribeNamespaceResponse;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Temporal: the namespace exists, and — in deep mode — work actually runs.
 *
 * The two halves answer different questions and only the second one matters to
 * an app. DescribeNamespace proves the frontend is up and the tenant's namespace
 * was provisioned. Running a workflow proves something far more specific: that a
 * worker in THIS process polled THIS task queue, picked the task up, executed an
 * activity and returned. Every real failure of the Temporal seam — a namespace
 * that was never created, a task queue nothing polls, a worker that died on
 * registration — shows up in the second half and passes the first.
 */
@Component
@Order(50)
public class TemporalProbe implements Probe {

    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(20);

    private final TemporalProperties properties;
    private final PlumbProperties plumb;
    private final TemporalSeam seam;

    public TemporalProbe(TemporalProperties properties, PlumbProperties plumb, TemporalSeam seam) {
        this.properties = properties;
        this.plumb = plumb;
        this.seam = seam;
    }

    @Override
    public String id() {
        return "temporal";
    }

    @Override
    public ProbeGroup group() {
        return ProbeGroup.PLATFORM;
    }

    @Override
    public String title() {
        return "Temporal";
    }

    @Override
    public String proves() {
        return "the tenant namespace exists and a worker in this process runs a workflow end to end";
    }

    @Override
    public Outcome run() {
        if (!properties.configured()) {
            return Outcome.skipped("TEMPORAL_ADDRESS / TEMPORAL_NAMESPACE / TEMPORAL_TASK_QUEUE not set");
        }

        TemporalSeam.Session session;
        try {
            session = seam.connect();
        } catch (RuntimeException unreachable) {
            seam.discard();
            throw unreachable;
        }

        try {
            DescribeNamespaceResponse namespace = session.stubs()
                    .blockingStub()
                    .describeNamespace(DescribeNamespaceRequest.newBuilder()
                            .setNamespace(properties.namespace())
                            .build());
            String retention = namespace.getConfig().getWorkflowExecutionRetentionTtl().getSeconds() / 86400 + "d";

            if (!plumb.deep()) {
                return Outcome.ok(
                        "namespace " + properties.namespace() + " exists (deep mode off)",
                        "address: " + properties.address(),
                        "retention: " + retention,
                        "set PLUMB_DEEP=true to run a real workflow");
            }

            String token = UUID.randomUUID().toString();
            PingWorkflow workflow = session.client()
                    .newWorkflowStub(
                            PingWorkflow.class,
                            WorkflowOptions.newBuilder()
                                    .setWorkflowId(PingWorkflow.workflowId(token))
                                    .setTaskQueue(properties.taskQueue())
                                    .setWorkflowExecutionTimeout(EXECUTION_TIMEOUT)
                                    .build());
            String echoed = workflow.ping(token);

            if (echoed == null || !echoed.startsWith(token)) {
                return Outcome.fail("workflow returned " + echoed + ", expected an echo of " + token);
            }
            return Outcome.ok(
                    "namespace " + properties.namespace() + ", workflow round trip verified",
                    "address: " + properties.address(),
                    "retention: " + retention,
                    "task queue: " + properties.taskQueue(),
                    "executed by: " + echoed.substring(token.length() + 1));
        } catch (RuntimeException failed) {
            // A failure here can mean the connection went stale (frontend
            // restart, namespace deleted), so the cached session is dropped and
            // the next sweep starts clean rather than repeating a dead handle.
            seam.discard();
            throw failed;
        }
    }
}
