package haven.loom.persistence.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LogRepository extends JpaRepository<LogEntity, UUID> {

    List<LogEntity> findByCaseIdOrderByTimestampAsc(UUID caseId);

    Optional<LogEntity> findFirstByActionOrderByTimestampDesc(String action);

    void deleteByCaseId(UUID caseId);

    // JPQL over the mapped entities (not native SQL) so the jsonb and
    // timestamptz columns come back with the entity-declared Java types.
    @Query("SELECT new haven.loom.persistence.jpa.ReviewRow("
            + "l.caseId, c.title, l.message, l.details, l.timestamp) "
            + "FROM LogEntity l JOIN CaseEntity c ON l.caseId = c.id "
            + "WHERE l.action = 'reviewed' ORDER BY l.timestamp DESC")
    List<ReviewRow> recentReviews(Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query(
            value = "UPDATE loom.logs SET details = coalesce(details, '{}'::jsonb) || cast(:patch AS jsonb) "
                    + "WHERE id = (SELECT id FROM loom.logs WHERE case_id = :caseId AND action = 'reviewed' "
                    + "ORDER BY timestamp DESC LIMIT 1)",
            nativeQuery = true)
    void mergeDetailsIntoLatestReview(@Param("caseId") UUID caseId, @Param("patch") String patch);
}
