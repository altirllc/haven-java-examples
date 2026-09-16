package haven.loom.agent;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a failed startup gateway probe into Boot's clean
 * APPLICATION FAILED TO START block instead of a bean-creation stack trace.
 * Registered in META-INF/spring.factories.
 */
public class ModelsGatewayFailureAnalyzer extends AbstractFailureAnalyzer<ModelsGatewayUnavailableException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ModelsGatewayUnavailableException cause) {
        return new FailureAnalysis(
                "The model gateway rejected this app's probe:\n\n" + cause.getMessage(),
                "Fill models-gateway.endpoint and models-gateway.api-key in "
                        + "backend/{api,jobs}/src/main/resources/application-local.yaml (local dev; port-forward "
                        + "LiteLLM and use a tenant's key from its tenant-models-secret), or check the "
                        + "tenant-models-secret secret in the tenant's vCluster.",
                cause);
    }
}
