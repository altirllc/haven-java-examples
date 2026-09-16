package haven.plumb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tenant object storage (SeaweedFS, S3-compatible), from tenant-s3-secret.
 *
 * The bucket is the tenant's and is shared by every app in it, so each app owns
 * a key prefix and never the root. plumb writes under plumb/ and deletes what it
 * writes.
 */
@ConfigurationProperties(prefix = "storage.s3")
public record StorageProperties(
        String endpoint, String accessKey, String secretKey, String bucket, String prefix, boolean useSsl) {

    public boolean configured() {
        return Values.allSet(endpoint, accessKey, secretKey, bucket);
    }
}
