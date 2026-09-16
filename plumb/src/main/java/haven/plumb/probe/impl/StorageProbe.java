package haven.plumb.probe.impl;

import haven.plumb.config.StorageProperties;
import haven.plumb.probe.Probe;
import haven.plumb.probe.ProbeGroup;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Tenant object storage: put, get, list, delete — under this app's prefix.
 *
 * Two platform specifics are load-bearing and both are proven here rather than
 * assumed. SeaweedFS speaks PATH-style addressing, so a client left on the AWS
 * default of virtual-host style resolves a bucket subdomain that does not exist.
 * And the bucket is the TENANT's, shared by every app in it, so an app owns a key
 * prefix and never the root — this probe writes under {prefix} and deletes what
 * it wrote.
 *
 * A missing bucket is reported, not created. Bucket creation belongs to tenant
 * provisioning; an app that silently creates it hides a provisioning gap and
 * then owns a bucket nobody meant it to own.
 */
@Component
@Order(40)
public class StorageProbe implements Probe {

    private final StorageProperties properties;

    public StorageProbe(StorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return "s3";
    }

    @Override
    public ProbeGroup group() {
        return ProbeGroup.DATA;
    }

    @Override
    public String title() {
        return "Object storage (S3 / SeaweedFS)";
    }

    @Override
    public String proves() {
        return "the tenant bucket exists and this app can write, read, list and delete under its prefix";
    }

    @Override
    public Outcome run() {
        if (!properties.configured()) {
            return Outcome.skipped(
                    "STORAGE_S3_ENDPOINT / _ACCESS_KEY / _SECRET_KEY / _BUCKET not set" + " — from tenant-s3-secret");
        }

        String scheme = properties.useSsl() ? "https://" : "http://";
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(scheme + properties.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                // The region is required by the SDK and ignored by SeaweedFS.
                .region(Region.US_EAST_1)
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {

            try {
                client.headBucket(HeadBucketRequest.builder()
                        .bucket(properties.bucket())
                        .build());
            } catch (NoSuchBucketException missing) {
                return Outcome.fail(
                        "bucket " + properties.bucket() + " does not exist",
                        "the bucket is created during tenant provisioning, not by an app");
            }

            String key = properties.prefix() + "heartbeat-" + UUID.randomUUID() + ".txt";
            String body = "plumb round trip at " + Instant.now();
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType("text/plain")
                            .build(),
                    RequestBody.fromString(body, StandardCharsets.UTF_8));

            String readBack = client.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .build())
                    .asUtf8String();

            int underPrefix = client.listObjectsV2(ListObjectsV2Request.builder()
                            .bucket(properties.bucket())
                            .prefix(properties.prefix())
                            .build())
                    .keyCount();

            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());

            if (!body.equals(readBack)) {
                return Outcome.fail("object " + key + " read back with different content");
            }
            return Outcome.ok(
                    "bucket " + properties.bucket() + ", round trip verified",
                    "key: " + key,
                    "objects under " + properties.prefix() + " before delete: " + underPrefix,
                    "addressing: path-style");
        }
    }
}
