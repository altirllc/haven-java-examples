package haven.loom.persistence;

import haven.loom.domain.Plan;
import haven.loom.persistence.jpa.UsageEventEntity;
import haven.loom.persistence.jpa.UsageEventRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The metering gate for loom's plan enforcement.
 *
 * `loom.usage_events` is the record AND the enforcer: `consumeCase` appends
 * the row and renders its verdict inside the caller's transaction under an
 * advisory lock, so concurrent creations get serialized, consistent verdicts.
 * Prometheus and the platform only ever see totals derived from these rows;
 * nothing outside this database is consulted to admit work.
 *
 * `free` refuses at the allowance, `paid` continues and the excess is what
 * gets billed. The unit (one item) is known before any work runs, so a
 * refusal consumes nothing — the gate closes at exactly the allowance. Only
 * creation meters: the triager's decisions on existing items consume nothing.
 */
@Service
public class UsageService {

    /** Advisory-lock key serializing the check-and-append. Locks are database-
     *  scoped and the tenant database is shared by every app, so the key derives
     *  from the app name — rendered apps are unique without hand-editing. */
    private static final long USAGE_LOCK_KEY = lockKey("loom");

    /**
     * The metered unit. Public because the `haven_usage` gauge in jobs tags
     * itself with it: the ledger and the metric must never disagree about what
     * is being counted, and a second literal is how they start to.
     * Matches the `unit` in the catalog entry's plans block.
     */
    public static final String UNIT_CASES = "cases";

    private final UsageEventRepository events;
    private final PlanProperties plan;

    public UsageService(UsageEventRepository events, PlanProperties plan) {
        this.events = events;
        this.plan = plan;
    }

    /**
     * Atomically record-and-admit one item. Joins the caller's transaction so
     * the usage row and the item row commit or roll back together; a refusal
     * throws before anything is written.
     */
    void consumeCase(UUID caseId) {
        events.acquireUsageLock(USAGE_LOCK_KEY);
        long used = events.sumByUnit(UNIT_CASES);
        // No idempotency lookup: item ids are minted fresh per request, so a
        // retry can never re-present an already-counted id.
        if (plan.name() == Plan.FREE && used + 1 > plan.freeCases()) {
            throw new PlanLimitReachedException(plan.freeCases());
        }
        events.save(new UsageEventEntity(usageId(caseId), caseId, UNIT_CASES, 1, CaseService.now()));
    }

    void discard(UUID caseId) {
        events.deleteById(usageId(caseId));
    }

    /** Lifetime items total, from the append-only ledger. */
    public long used() {
        return events.sumByUnit(UNIT_CASES);
    }

    private static String usageId(UUID caseId) {
        return caseId + ":" + UNIT_CASES;
    }

    /** 32-bit FNV-1a of the app name. */
    private static long lockKey(String app) {
        int h = 0x811c9dc5;
        for (int i = 0; i < app.length(); i++) {
            h ^= app.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }
}
