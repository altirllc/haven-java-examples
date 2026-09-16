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
 * Row mapping for loom.logs. Deliberately no @ManyToOne to ItemEntity: the
 * audit log is append-only and read by item id, so a raw FK column keeps the
 * mapping free of lazy-loading state.
 */
@Entity
@Table(name = "logs")
public class LogEntity {

    @Id
    private UUID id;

    @Column(name = "case_id")
    private UUID caseId;

    private OffsetDateTime timestamp;

    private String action;

    private String actor;

    private String channel;

    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    private String details;

    protected LogEntity() {}

    public LogEntity(
            UUID id,
            UUID caseId,
            OffsetDateTime timestamp,
            String action,
            String actor,
            String channel,
            String message,
            String details) {
        this.id = id;
        this.caseId = caseId;
        this.timestamp = timestamp;
        this.action = action;
        this.actor = actor;
        this.channel = channel;
        this.message = message;
        this.details = details;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public String getAction() {
        return action;
    }

    public String getActor() {
        return actor;
    }

    public String getChannel() {
        return channel;
    }

    public String getMessage() {
        return message;
    }

    public String getDetails() {
        return details;
    }
}
