package haven.loom.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * loom's supervision record for one Anvil item.
 *
 * A case is NOT the item. Anvil owns the item, its status and its triage agent's
 * decision; loom owns an opinion about that decision and a clock running against
 * it. `anvilItemId` is the join, and it is unique — one item, one case.
 *
 * The snake_case JSON names are the wire contract: the React UI reads them
 * verbatim (frontend/src/api/client.ts). The agent endpoints use camelCase, so no
 * global naming strategy applies.
 *
 * `anvilItemId` is a String, not a UUID, for the same reason
 * {@link AnvilDecision}'s fields are Strings: it is an identifier minted by
 * another system, and loom's job is to carry it faithfully rather than to have
 * opinions about its shape.
 */
public record Case(
        UUID id,
        @JsonProperty("anvil_item_id") String anvilItemId,
        String title,
        CaseState state,
        @JsonProperty("anvil_status") String anvilStatus,
        @JsonProperty("anvil_decision") AnvilDecision anvilDecision,
        Review review,
        @JsonProperty("sla_due_at") OffsetDateTime slaDueAt,
        Map<String, Object> metadata,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt) {

    /** The priority loom's review assigned, if it has reviewed. */
    public Priority priority() {
        return review == null ? null : review.priority();
    }

    /** Whether the SLA has run out — evaluated against a caller-supplied clock. */
    public boolean overdue(OffsetDateTime now) {
        return slaDueAt != null && state.isOpen() && now.isAfter(slaDueAt);
    }
}
