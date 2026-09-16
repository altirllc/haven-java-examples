package haven.loom.persistence.jpa;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseRepository extends JpaRepository<CaseEntity, UUID> {

    /** Ingest's idempotency check: one Anvil item, one case. */
    Optional<CaseEntity> findByAnvilItemId(String anvilItemId);

    List<CaseEntity> findByState(String state, Pageable pageable);

    // List-returning on purpose: findAll(Pageable) yields a Page, and a Page
    // costs an extra COUNT(*) on every call.
    List<CaseEntity> findAllBy(Pageable pageable);

    /** Open cases, oldest deadline first — what the daemon sweeps each tick. */
    @Query(
            value = "SELECT * FROM loom.cases WHERE state IN (:states) ORDER BY sla_due_at ASC LIMIT :limit",
            nativeQuery = true)
    List<CaseEntity> findOpenBySlaDueAt(@Param("states") List<String> states, @Param("limit") int limit);

    long countByState(String state);

    long countByStateNot(String state);

    /**
     * Open cases whose deadline has passed. `now` is a parameter rather than
     * SQL's now(): the clock belongs to the service, and passing it in keeps
     * this answerable from a test without waiting.
     */
    @Query(
            value = "SELECT count(*) FROM loom.cases WHERE state IN (:states) AND sla_due_at < :now",
            nativeQuery = true)
    long countOverdue(@Param("states") List<String> states, @Param("now") OffsetDateTime now);

    @Query(
            value = "SELECT count(*) FROM loom.cases WHERE review ->> 'priority' = :priority",
            nativeQuery = true)
    long countByReviewPriority(@Param("priority") String priority);

    /**
     * The write the processCase workflow applies once a verdict lands. State and
     * review move together — a case in `disputed` with no review would be a
     * record of a decision nobody can explain.
     */
    @Modifying(clearAutomatically = true)
    @Query(
            value = "UPDATE loom.cases SET state = :state, review = cast(:review AS jsonb), "
                    + "updated_at = :now WHERE id = :id",
            nativeQuery = true)
    void applyReview(
            @Param("id") UUID id,
            @Param("state") String state,
            @Param("review") String review,
            @Param("now") OffsetDateTime now);

    /** The SLA ran out with no verdict. No review to write — that is the point. */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE loom.cases SET state = :state, updated_at = :now WHERE id = :id", nativeQuery = true)
    void updateState(@Param("id") UUID id, @Param("state") String state, @Param("now") OffsetDateTime now);

    /**
     * What ingest writes when it re-reads an item from Anvil. The metadata merge
     * happens server-side (coalesce || patch) so a concurrent edit is not
     * clobbered.
     */
    @Modifying(clearAutomatically = true)
    @Query(
            value = "UPDATE loom.cases SET title = :title, anvil_status = :anvilStatus, "
                    + "anvil_decision = cast(:anvilDecision AS jsonb), updated_at = :now WHERE id = :id",
            nativeQuery = true)
    void syncFromAnvil(
            @Param("id") UUID id,
            @Param("title") String title,
            @Param("anvilStatus") String anvilStatus,
            @Param("anvilDecision") String anvilDecision,
            @Param("now") OffsetDateTime now);
}
