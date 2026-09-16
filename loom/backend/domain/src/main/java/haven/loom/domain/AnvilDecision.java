package haven.loom.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * What Anvil decided about the item, as loom last saw it.
 *
 * Every field is a String, deliberately. This is ANVIL's vocabulary, not
 * loom's: Anvil owns the set of verbs its triager can reach for, and it is free
 * to add one without telling us. Typing these as enums would mean a new Anvil
 * verb deserialises into an exception and takes loom's ingest down over a
 * decision it only needed to record. loom's own verdict IS typed — see
 * {@link Review} — because that vocabulary is ours to change.
 *
 * `null` throughout means "Anvil has not decided yet", which is a normal state
 * for a freshly opened case and the thing an SLA is measured against.
 */
public record AnvilDecision(
        String action,
        String priority,
        String rationale,
        @JsonProperty("at") OffsetDateTime at) {}
