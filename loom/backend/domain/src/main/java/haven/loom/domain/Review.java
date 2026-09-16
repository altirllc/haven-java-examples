package haven.loom.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * loom's verdict on a case: what it decided, how urgent, and why.
 *
 * Typed, unlike {@link AnvilDecision}, because this vocabulary is loom's own —
 * an unknown value here is a bug in loom, not a change upstream.
 *
 * `rationale` is not decoration. A supervisor that only says "dispute" is
 * useless to the human who has to act on it; the sentence is the product.
 */
public record Review(
        ReviewAction action,
        Priority priority,
        String rationale,
        @JsonProperty("at") OffsetDateTime at) {}
