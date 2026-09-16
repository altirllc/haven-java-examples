package haven.loom.orchestration;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Temporal explicitly rather than via temporal-spring-boot-starter.
 *
 * The starter discovers workflow implementations through @WorkflowImpl — an
 * annotation that ships inside the starter itself, so using it would force
 * :workflows to depend on the starter and put Spring on the workflow classpath.
 * That is the one mechanism that would breach the determinism fence, so the
 * scaffold hand-wires the ~20 lines instead. :jobs registers workflow classes
 * explicitly.
 */
@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
public class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(TemporalProperties properties) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(properties.address())
                        .build());
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs, TemporalProperties properties) {
        return WorkflowClient.newInstance(
                stubs,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(properties.namespace())
                        .build());
    }
}
