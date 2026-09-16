package haven.plumb.probe;

import java.time.Instant;
import java.util.List;

/**
 * A probe's outcome plus its identity and timing — the shape served by
 * {@code /api/checks} and rendered on the page.
 */
public record ProbeResult(
        String id,
        String group,
        String title,
        String proves,
        ProbeStatus status,
        String detail,
        List<String> evidence,
        long latencyMs,
        Instant checkedAt) {

    public boolean ok() {
        return status == ProbeStatus.OK;
    }

    public boolean failed() {
        return status == ProbeStatus.FAIL;
    }

    /** Placeholder for a probe that has not run yet — the page is never empty. */
    public static ProbeResult pending(Probe probe) {
        return new ProbeResult(
                probe.id(),
                probe.group().title(),
                probe.title(),
                probe.proves(),
                ProbeStatus.SKIPPED,
                "not run yet",
                List.of(),
                0,
                Instant.EPOCH);
    }
}
