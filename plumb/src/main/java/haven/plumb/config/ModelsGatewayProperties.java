package haven.plumb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The platform model gateway (LiteLLM in haven-models), from tenant-models-secret.
 *
 * Apps carry no vendor credentials: they authenticate with the tenant's virtual
 * key and call model ALIASES that name the role rather than the vendor model
 * behind it. That indirection is the thing worth proving — an alias the gateway
 * does not serve is the failure an app would otherwise hit on its first
 * inference, long after deployment looked green.
 */
@ConfigurationProperties(prefix = "models-gateway")
public record ModelsGatewayProperties(
        String endpoint, String apiKey, String chatModel, String embeddingModel, int embeddingDimensions) {

    public boolean configured() {
        return Values.allSet(endpoint, apiKey);
    }
}
