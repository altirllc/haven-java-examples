package haven.plumb.probe;

import haven.plumb.config.PlumbProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the probes and holds the latest result for each.
 *
 * This class is where fail-open is implemented. A probe that throws becomes a
 * FAIL row; a probe that hangs becomes a FAIL row once the timeout expires.
 * Nothing a probe does can take the process down or stall the page — which is
 * what lets the probes themselves stay free of defensive plumbing and read like
 * the round trip they perform.
 */
@Component
public class ProbeRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProbeRegistry.class);

    private final List<Probe> probes;
    private final PlumbProperties properties;
    private final Map<String, ProbeResult> latest = new ConcurrentHashMap<>();

    /**
     * Long-lived on purpose. A per-run executor would have to be closed, and
     * close() blocks until every task finishes — which is precisely the hung
     * probe the timeout exists to escape.
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ProbeRegistry(List<Probe> probes, List<ProbeSource> sources, PlumbProperties properties) {
        // Spring hands injected collections over already sorted by @Order, so a
        // stable sort by group keeps each group's declared order inside it.
        this.probes = Stream.concat(probes.stream(), sources.stream().flatMap(source -> source.probes().stream()))
                .sorted(Comparator.comparingInt(probe -> probe.group().ordinal()))
                .toList();
        this.properties = properties;
        this.probes.forEach(probe -> latest.put(probe.id(), ProbeResult.pending(probe)));
    }

    public List<Probe> probes() {
        return probes;
    }

    /** Latest known result per probe, in page order. Never empty, never null entries. */
    public List<ProbeResult> results() {
        return probes.stream().map(probe -> latest.get(probe.id())).toList();
    }

    public Optional<ProbeResult> result(String id) {
        return Optional.ofNullable(latest.get(id));
    }

    /** Results bucketed for rendering, preserving group and within-group order. */
    public Map<String, List<ProbeResult>> grouped() {
        Map<String, List<ProbeResult>> groups = new LinkedHashMap<>();
        for (ProbeResult result : results()) {
            groups.computeIfAbsent(result.group(), key -> new ArrayList<>()).add(result);
        }
        return groups;
    }

    /** Run every probe concurrently and return the fresh results in page order. */
    public List<ProbeResult> runAll() {
        Map<String, Future<ProbeResult>> running = new LinkedHashMap<>();
        for (Probe probe : probes) {
            running.put(probe.id(), executor.submit(() -> timed(probe)));
        }
        // One deadline for the sweep, not one per probe: the probes run
        // concurrently, so a shared deadline is both simpler and what an
        // operator watching the page actually experiences.
        Instant deadline = Instant.now().plus(properties.probeTimeout());
        for (Probe probe : probes) {
            latest.put(probe.id(), collect(probe, running.get(probe.id()), deadline));
        }
        Summary summary = summary();
        log.info(
                "Probe sweep complete: {} ok, {} failed, {} skipped",
                summary.ok(),
                summary.failed(),
                summary.skipped());
        return results();
    }

    /** Run one probe by id. Empty when there is no such probe. */
    public Optional<ProbeResult> runOne(String id) {
        return probes.stream().filter(probe -> probe.id().equals(id)).findFirst().map(probe -> {
            Instant deadline = Instant.now().plus(properties.probeTimeout());
            ProbeResult result = collect(probe, executor.submit(() -> timed(probe)), deadline);
            latest.put(probe.id(), result);
            return result;
        });
    }

    public Summary summary() {
        List<ProbeResult> results = results();
        return new Summary(
                results.size(),
                (int) results.stream().filter(ProbeResult::ok).count(),
                (int) results.stream().filter(ProbeResult::failed).count(),
                (int) results.stream()
                        .filter(result -> result.status() == ProbeStatus.SKIPPED)
                        .count(),
                results.stream()
                        .map(ProbeResult::checkedAt)
                        .filter(checkedAt -> checkedAt.isAfter(Instant.EPOCH))
                        .max(Instant::compareTo)
                        .orElse(null));
    }

    public record Summary(int total, int ok, int failed, int skipped, Instant lastRun) {

        /** Red only for a real failure — an unconfigured seam is not a problem. */
        public boolean healthy() {
            return failed == 0;
        }
    }

    private ProbeResult timed(Probe probe) {
        long startedAt = System.nanoTime();
        try {
            return stamp(probe, probe.run(), startedAt);
        } catch (Exception failure) {
            // The single place a seam failure becomes data instead of an outage.
            return stamp(probe, Probe.Outcome.fail(Failures.describe(failure)), startedAt);
        }
    }

    private ProbeResult collect(Probe probe, Future<ProbeResult> future, Instant deadline) {
        long remaining = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
        try {
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            // Cancellation is best-effort: a driver blocked in a socket read may
            // ignore the interrupt and finish into the void. The row is still
            // honest and the page still renders, which is what matters.
            future.cancel(true);
            return stamp(
                    probe,
                    Probe.Outcome.fail("timed out after " + properties.probeTimeout().toSeconds() + "s"),
                    System.nanoTime() - properties.probeTimeout().toNanos());
        } catch (ExecutionException failed) {
            return stamp(probe, Probe.Outcome.fail(Failures.describe(failed.getCause())), System.nanoTime());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return stamp(probe, Probe.Outcome.fail("interrupted"), System.nanoTime());
        }
    }

    private ProbeResult stamp(Probe probe, Probe.Outcome outcome, long startedAt) {
        return new ProbeResult(
                probe.id(),
                probe.group().title(),
                probe.title(),
                probe.proves(),
                outcome.status(),
                outcome.detail(),
                outcome.evidence(),
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                Instant.now());
    }
}
