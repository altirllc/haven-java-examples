package haven.plumb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Temporal, replicated into the tenant vCluster at workflows-temporal.default:7233.
 *
 * The namespace is the tenant id — one namespace per tenant — and the task queue
 * is the app name, which is also why the worker has to be plumb's own: a probe
 * that polled another app's queue would steal that app's work.
 *
 * @param deep start a real workflow rather than only describing the namespace. A
 *     namespace lookup proves the frontend is up and says nothing about whether
 *     work can actually run, but the sweep runs every 60s, so proving it costs a
 *     workflow execution a minute forever. Off by default. The flag lives here
 *     rather than on PlumbProperties so its name follows its seam — homing it on
 *     `plumb` is what let the feature id and the variable drift apart.
 */
@ConfigurationProperties(prefix = "temporal")
public record TemporalProperties(String address, String namespace, String taskQueue, boolean deep) {

    public boolean configured() {
        return Values.allSet(address, namespace, taskQueue);
    }
}
