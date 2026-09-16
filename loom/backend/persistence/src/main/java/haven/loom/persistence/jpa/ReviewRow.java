package haven.loom.persistence.jpa;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A `reviewed` row joined to its case title — the live review feed. */
public record ReviewRow(UUID caseId, String title, String message, String details, OffsetDateTime at) {}
