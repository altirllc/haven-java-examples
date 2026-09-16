package haven.loom.agent;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The platform model gateway (LiteLLM in haven-models), reached through the
 * models-litellm mirror with the tenant's virtual key from tenant-models-secret.
 * Model names are gateway aliases — the name the API is called with IS the
 * client-facing model name; the gateway maps it to the upstream deployment.
 */
@Validated
@ConfigurationProperties(prefix = "models-gateway")
public record ModelsGatewayProperties(
        @NotBlank String endpoint,
        @NotBlank String apiKey,
        @NotBlank String chatModel,
        @NotBlank String embeddingModel) {
}
