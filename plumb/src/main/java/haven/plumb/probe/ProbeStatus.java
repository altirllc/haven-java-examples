package haven.plumb.probe;

/** The three outcomes a seam check can have. */
public enum ProbeStatus {
    /** The round trip completed. */
    OK,
    /** The seam is configured but did not work — this is the one that matters. */
    FAIL,
    /**
     * The seam is not configured here, so there was nothing to test. Never an
     * error: a tenant that does not use S3 should not show a red row for it.
     * The detail always names the variable that would enable the check.
     */
    SKIPPED
}
