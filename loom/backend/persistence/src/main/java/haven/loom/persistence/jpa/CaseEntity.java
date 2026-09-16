package haven.loom.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row mapping for loom.cases. The jsonb columns stay String here — Jackson
 * (de)serialization is the case service's job, so Hibernate never needs a format
 * mapper and the entity carries no object-graph state.
 */
@Entity
@Table(name = "cases")
public class CaseEntity {

    @Id
    private UUID id;

    @Column(name = "anvil_item_id")
    private String anvilItemId;

    private String title;

    private String state;

    @Column(name = "anvil_status")
    private String anvilStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "anvil_decision")
    private String anvilDecision;

    @JdbcTypeCode(SqlTypes.JSON)
    private String review;

    @Column(name = "sla_due_at")
    private OffsetDateTime slaDueAt;

    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected CaseEntity() {}

    public CaseEntity(
            UUID id,
            String anvilItemId,
            String title,
            String state,
            String anvilStatus,
            String anvilDecision,
            String review,
            OffsetDateTime slaDueAt,
            String metadata,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        this.id = id;
        this.anvilItemId = anvilItemId;
        this.title = title;
        this.state = state;
        this.anvilStatus = anvilStatus;
        this.anvilDecision = anvilDecision;
        this.review = review;
        this.slaDueAt = slaDueAt;
        this.metadata = metadata;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getAnvilItemId() {
        return anvilItemId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getAnvilStatus() {
        return anvilStatus;
    }

    public void setAnvilStatus(String anvilStatus) {
        this.anvilStatus = anvilStatus;
    }

    public String getAnvilDecision() {
        return anvilDecision;
    }

    public void setAnvilDecision(String anvilDecision) {
        this.anvilDecision = anvilDecision;
    }

    public String getReview() {
        return review;
    }

    public void setReview(String review) {
        this.review = review;
    }

    public OffsetDateTime getSlaDueAt() {
        return slaDueAt;
    }

    public void setSlaDueAt(OffsetDateTime slaDueAt) {
        this.slaDueAt = slaDueAt;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
