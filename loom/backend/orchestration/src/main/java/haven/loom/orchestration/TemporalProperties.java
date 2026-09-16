package haven.loom.orchestration;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Temporal connection settings, bound from TEMPORAL_* and validated at boot.
 *
 * 12-factor: config comes from the environment and a missing value fails the
 * process immediately rather than at the first signal.
 */
@Validated
@ConfigurationProperties(prefix = "temporal")
public record TemporalProperties(
        @NotBlank String address,
        @NotBlank String namespace,
        @NotBlank String taskQueue) {
}
