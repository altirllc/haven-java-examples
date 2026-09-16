package haven.loom.jobs;

import haven.loom.persistence.UsageService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * `haven_usage` — the platform's window onto plan usage, recomputed from the
 * durable usage ledger on every scrape so a pod restart re-reports the same
 * totals. Lives in jobs because its `management` port is the pod's one
 * auto-scraped actuator surface; a single exporter keeps the series
 * single-sourced.
 */
@Component
class UsageMetrics {

    private final UsageService usage;
    private final AtomicLong lastKnown = new AtomicLong();

    UsageMetrics(UsageService usage, MeterRegistry registry) {
        this.usage = usage;
        Gauge.builder("haven_usage", this::casesUsed)
                .tag("unit", UsageService.UNIT_CASES)
                .description("Lifetime usage per unit, derived from the append-only usage ledger")
                .register(registry);
    }

    // A failed read serves the last-known value — a scrape must never fail.
    private double casesUsed() {
        try {
            lastKnown.set(usage.used());
        } catch (RuntimeException ignored) {
        }
        return lastKnown.get();
    }
}
