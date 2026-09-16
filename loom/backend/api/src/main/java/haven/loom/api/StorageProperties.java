package haven.loom.api;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Tenant object storage, from the tenant-s3-secret secret. api-only. */
@Validated
@ConfigurationProperties(prefix = "storage.s3")
public record StorageProperties(
        @NotBlank String endpoint,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotBlank String bucket,
        boolean useSsl) {
}
