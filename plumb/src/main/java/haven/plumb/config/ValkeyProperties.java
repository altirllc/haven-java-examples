package haven.plumb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The tenant's Valkey, addressed by host and port rather than a URI.
 *
 * The variables are REDIS_HOST and REDIS_PORT, not VALKEY_*: that is the shape
 * the platform already injects (see the dispatch bundle), and plumb's job is to
 * prove the contract an app is actually wired to rather than a tidier one. The
 * cache carries no credential — it is reachable to anything inside the tenant's
 * own vCluster and nothing outside it.
 */
@ConfigurationProperties(prefix = "valkey")
public record ValkeyProperties(String host, String port, String keyPrefix) {

    /**
     * A String like every other seam property here, not an int.
     *
     * Binding straight to int would kill the process on a value that does not
     * parse, which is the one thing plumb must never do — and Kubernetes hands
     * it exactly such a value: a Service named `valkey` in the same namespace
     * makes the kubelet set VALKEY_PORT="tcp://10.x.x.x:6379", which outranks
     * application.yaml. The pod disables service links so that never arrives,
     * but the type is what makes the guarantee rather than the manifest.
     */
    public boolean configured() {
        return Values.allSet(host) && portOrZero() > 0;
    }

    /** The port as a number, or 0 when it is unset or not one. */
    public int portOrZero() {
        if (!Values.isSet(port)) {
            return 0;
        }
        try {
            return Integer.parseInt(port.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
