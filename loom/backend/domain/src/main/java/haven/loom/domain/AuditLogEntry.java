package haven.loom.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One row of a case's audit log — the case detail timeline.
 *
 * `actor` is WHO acted and is deliberately an open string: a human's own id, or
 * the non-human principals 'agent' / 'system'. `channel` is HOW it arrived and
 * is a closed set.
 */
public record AuditLogEntry(
        UUID id,
        @JsonProperty("case_id") UUID caseId,
        OffsetDateTime timestamp,
        String action,
        String actor,
        Channel channel,
        String message,
        Map<String, Object> details) {

    public static final String ACTOR_AGENT = "agent";
    public static final String ACTOR_SYSTEM = "system";
}
