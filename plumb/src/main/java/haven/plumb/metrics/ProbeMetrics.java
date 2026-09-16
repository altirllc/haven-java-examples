package haven.plumb.metrics;

import haven.plumb.probe.Probe;
import haven.plumb.probe.ProbeRegistry;
import haven.plumb.probe.ProbeResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The seams as Prometheus gauges, so a cell can alert on them rather than
 * waiting for someone to open the page.
 *
 * Gauges are registered once and read the registry live, so the scrape always
 * reflects the most recent sweep rather than whatever the last scrape captured.
 *
 * SKIPPED reports NaN, not 0. The distinction is the whole point: 0 means "this
 * seam is broken" and must page someone, while a seam the tenant does not use
 * has no value at all. Encoding "not applicable" as 0 would make every correctly
 * provisioned tenant look like an outage, and encoding it as 1 would hide real
 * breakage behind a green metric.
 */
@Component
public class ProbeMetrics {

    public ProbeMetrics(MeterRegistry meters, ProbeRegistry probes) {
        for (Probe probe : probes.probes()) {
            Gauge.builder("haven_probe_up", probes, registry -> statusOf(registry, probe.id()))
                    .description("1 when the seam round trip succeeded, 0 when it failed, NaN when not configured")
                    .tag("seam", probe.id())
                    .tag("group", probe.group().name().toLowerCase())
                    .register(meters);

            Gauge.builder("haven_probe_latency_seconds", probes, registry -> latencyOf(registry, probe.id()))
                    .description("How long the seam round trip took on the last sweep")
                    .tag("seam", probe.id())
                    .tag("group", probe.group().name().toLowerCase())
                    .register(meters);
        }
    }

    private double statusOf(ProbeRegistry probes, String id) {
        return probes.result(id)
                .map(result -> switch (result.status()) {
                    case OK -> 1d;
                    case FAIL -> 0d;
                    case SKIPPED -> Double.NaN;
                })
                .orElse(Double.NaN);
    }

    private double latencyOf(ProbeRegistry probes, String id) {
        return probes.result(id)
                .filter(result -> result.status() != haven.plumb.probe.ProbeStatus.SKIPPED)
                .map(ProbeResult::latencyMs)
                .map(millis -> millis / 1000d)
                .orElse(Double.NaN);
    }
}
