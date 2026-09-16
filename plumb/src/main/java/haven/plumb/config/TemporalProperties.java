package haven.plumb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Temporal, replicated into the tenant vCluster at workflows-temporal.default:7233.
 *
 * The namespace is the tenant id — one namespace per tenant — and the task queue
 * is the app name, which is also why the worker has to be plumb's own: a probe
 * that polled another app's queue would steal that app's work.
 */
@ConfigurationProperties(prefix = "temporal")
public record TemporalProperties(String address, String namespace, String taskQueue) {

    public boolean configured() {
        return Values.allSet(address, namespace, taskQueue);
    }
}
