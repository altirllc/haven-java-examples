package haven.loom.api;

import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Attachments in the tenant bucket.
 *
 * Every key is prefixed `loom/` — one prefix per app inside the shared tenant
 * bucket. Never write to the bucket root.
 */
@Component
@EnableConfigurationProperties(StorageProperties.class)
public class Storage {

    private static final Logger log = LoggerFactory.getLogger(Storage.class);
    private static final String KEY_PREFIX = "loom/attachments/";

    private final S3Client client;
    private final StorageProperties properties;

    public Storage(StorageProperties properties) {
        this.properties = properties;
        this.client = S3Client.builder()
                .endpointOverride(URI.create((properties.useSsl() ? "https://" : "http://") + properties.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .region(Region.US_EAST_1)
                // SeaweedFS speaks path-style, not virtual-host-style.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    public String put(UUID caseId, String filename, byte[] body, String contentType) {
        String key = KEY_PREFIX + caseId + "/" + filename;
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(body));
        return key;
    }

    public void ensureBucket() {
        // SeaweedFS races the app at boot like every backing service. Bounded
        // wait, then the real error.
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
                return;
            } catch (NoSuchBucketException missing) {
                client.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
                log.info("Created bucket {}", properties.bucket());
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
