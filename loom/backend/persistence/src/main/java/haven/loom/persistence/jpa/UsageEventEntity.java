package haven.loom.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Row mapping for loom.usage_events — the append-only metering ledger. */
@Entity
@Table(name = "usage_events")
public class UsageEventEntity {

    @Id
    private String id;

    @Column(name = "case_id")
    private UUID caseId;

    private String unit;

    private int amount;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected UsageEventEntity() {}

    public UsageEventEntity(String id, UUID caseId, String unit, int amount, OffsetDateTime createdAt) {
        this.id = id;
        this.caseId = caseId;
        this.unit = unit;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getUnit() {
        return unit;
    }

    public int getAmount() {
        return amount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
