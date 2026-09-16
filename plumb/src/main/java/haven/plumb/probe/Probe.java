package haven.plumb.probe;

import java.util.List;

/**
 * One seam check.
 *
 * Implementations do the work and return an {@link Outcome}; they never catch
 * their own failures and never time themselves. {@link ProbeRegistry} converts a
 * thrown exception into {@link ProbeStatus#FAIL} and stamps the timing, so the
 * fail-open guarantee lives in exactly one place instead of being re-implemented
 * (and eventually got wrong) in every probe.
 *
 * A probe should WRITE AND READ BACK wherever the seam allows it. Opening a
 * connection proves the network; it does not prove credentials, permissions,
 * schema, or that the thing on the other end is the service you think it is.
 */
public interface Probe {

    /** Stable id — the metric label, the URL segment, the anchor on the page. */
    String id();

    ProbeGroup group();

    /** Human title for the page. */
    String title();

    /** What this proves, in one line, shown under the title. */
    String proves();

    Outcome run() throws Exception;

    /** What a probe reports. Evidence is what makes the row believable. */
    record Outcome(ProbeStatus status, String detail, List<String> evidence) {

        public static Outcome ok(String detail, String... evidence) {
            return new Outcome(ProbeStatus.OK, detail, List.of(evidence));
        }

        public static Outcome fail(String detail, String... evidence) {
            return new Outcome(ProbeStatus.FAIL, detail, List.of(evidence));
        }

        /** Not configured. The detail must name the variable that would turn it on. */
        public static Outcome skipped(String detail) {
            return new Outcome(ProbeStatus.SKIPPED, detail, List.of());
        }
    }
}
