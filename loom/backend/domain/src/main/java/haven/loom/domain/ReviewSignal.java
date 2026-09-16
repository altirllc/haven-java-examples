package haven.loom.domain;

import java.util.UUID;

/**
 * The verdict a reviewer sends to a case's processCase workflow.
 *
 * A human (via the REST routes) and the autonomous agent (via review-case) send
 * the IDENTICAL payload; only `actor` differs — 'agent' for the daemon, or a
 * human's own id. Neither writes case state directly.
 *
 * `actedAt` is a String rather than an OffsetDateTime: this crosses the workflow
 * boundary, and workflow code must not construct clock-derived values. The
 * caller stamps it.
 */
public record ReviewSignal(
        UUID caseId,
        String actor,
        Channel channel,
        ReviewAction action,
        Priority priority,
        String rationale,
        String actedAt) {}
