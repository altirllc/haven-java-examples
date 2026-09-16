package haven.loom.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageEventRepository extends JpaRepository<UsageEventEntity, String> {

    // Held until the surrounding transaction ends, serializing check-and-insert.
    // pg_advisory_xact_lock returns void; IS NULL gives the query a scalar to map.
    @Query(value = "SELECT pg_advisory_xact_lock(:key) IS NULL", nativeQuery = true)
    boolean acquireUsageLock(@Param("key") long key);

    @Query("SELECT coalesce(sum(u.amount), 0L) FROM UsageEventEntity u WHERE u.unit = :unit")
    long sumByUnit(@Param("unit") String unit);
}
