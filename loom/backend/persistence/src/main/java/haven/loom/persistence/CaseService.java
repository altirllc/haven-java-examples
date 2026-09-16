package haven.loom.persistence;

import haven.loom.domain.AnvilDecision;
import haven.loom.domain.AuditLogEntry;
import haven.loom.domain.Case;
import haven.loom.domain.CaseState;
import haven.loom.domain.Channel;
import haven.loom.domain.Review;
import haven.loom.persistence.jpa.CaseEntity;
import haven.loom.persistence.jpa.CaseRepository;
import haven.loom.persistence.jpa.LogEntity;
import haven.loom.persistence.jpa.LogRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * THE cases API — the application-layer boundary every actor calls, and the sole
 * gate to the JPA entities and repositories.
 *
 * Every table read and write goes through here, from every adapter: the REST
 * routes, the MCP tools, the agent's tools, and the Temporal activities. An
 * ArchUnit rule forbids anything outside this package touching
 * haven.loom.persistence.jpa, so there is one definition of how a case is
 * opened/synced/reviewed/read and one audit-log shape.
 *
 * NOT here: orchestration. Starting a case's processCase workflow and sending it
 * the review signal need a Temporal client, so they stay in the app processes.
 * Nor anything that talks to Anvil — this layer records what ingest found, it
 * does not go and find it.
 */
@Service
public class CaseService {

    private static final int DEFAULT_LIMIT = 50;

    /** The states a case can still be reviewed in — see CaseState.isOpen. */
    private static final List<String> OPEN_STATES =
            List.of(CaseState.WATCHING.toString(), CaseState.DISPUTED.toString());

    /**
     * The single clock. UTC + microseconds so an in-memory Case equals its own
     * timestamptz round-trip (Postgres normalizes to UTC and stores micros).
     */
    static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private final CaseRepository cases;
    private final LogRepository logs;
    private final UsageService usage;
    private final ObjectMapper json;

    public CaseService(CaseRepository cases, LogRepository logs, UsageService usage, ObjectMapper json) {
        this.cases = cases;
        this.logs = logs;
        this.usage = usage;
        this.json = json;
    }

    /** Order for listCases. TS exposes 'created' | 'updated'. */
    public enum Order {
        CREATED,
        UPDATED
    }

    /** Aggregate counts for the agent dashboard and the agent-status tool. */
    public record Stats(int watching, int reviewed, int overdue, OffsetDateTime lastReviewedAt) {}

    /** A `reviewed` row joined to its case title — the live review feed. */
    public record ReviewedCase(
            UUID caseId, String title, String rationale, Map<String, Object> details, OffsetDateTime at) {}

    /**
     * Open a case for an Anvil item, or return the one that already exists.
     *
     * Ingest runs on a timer and re-reads the same items, so this is idempotent
     * by design rather than by luck — and the unique constraint on anvilItemId
     * means a concurrent second ingest loses the insert rather than producing a
     * duplicate case. Orchestration (starting the processCase workflow) is the
     * caller's job.
     *
     * The metering gate and the insert share one transaction: a refusal
     * (PlanLimitReachedException) writes nothing, an admission cannot commit
     * without its usage row. Only OPENING meters — syncing and reviewing are
     * free, because the unit loom sells is a case supervised, not a database
     * write.
     */
    @Transactional
    public Case openCase(
            UUID id,
            String anvilItemId,
            String title,
            String anvilStatus,
            Duration sla,
            Map<String, Object> metadata,
            String actor,
            Channel channel) {
        Optional<CaseEntity> existing = cases.findByAnvilItemId(anvilItemId);
        if (existing.isPresent()) {
            return toCase(existing.get());
        }

        usage.consumeCase(id);
        OffsetDateTime now = now();
        CaseEntity entity = cases.save(new CaseEntity(
                id,
                anvilItemId,
                title,
                CaseState.WATCHING.toString(),
                anvilStatus,
                null,
                null,
                now.plus(sla),
                toJson(metadata == null ? Map.of() : metadata),
                now,
                now));

        writeLog(id, "opened", actor, channel, "Watching Anvil item " + anvilItemId, Map.of());
        return toCase(entity);
    }

    /**
     * Record what Anvil currently says about the item. Empty if no case has that
     * id.
     *
     * Deliberately NOT gated on isOpen: Anvil keeps deciding whether or not loom
     * has finished reviewing, and a closed case that later changes upstream is
     * worth having on the record. What this must never do is touch loom's own
     * verdict.
     */
    @Transactional
    public Optional<Case> syncFromAnvil(
            UUID id, String title, String anvilStatus, AnvilDecision decision, String actor, Channel channel) {
        Optional<CaseEntity> found = cases.findById(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CaseEntity entity = found.get();
        boolean decisionIsNew = decision != null && !toJson(decision).equals(entity.getAnvilDecision());

        cases.syncFromAnvil(id, title, anvilStatus, decision == null ? null : toJson(decision), now());

        if (decisionIsNew) {
            writeLog(
                    id,
                    "anvil-decided",
                    actor,
                    channel,
                    "Anvil decided: " + decision.action(),
                    Map.of("action", String.valueOf(decision.action()), "rationale", nullToEmpty(decision.rationale())));
        }
        return cases.findById(id).map(this::toCase);
    }

    /**
     * Apply a partial metadata update and log it. Empty if no case has that id;
     * throws CaseClosedException once the case has closed — a closed record
     * refuses edits from every surface (REST and MCP alike), enforced here so the
     * adapters cannot disagree. State writes for the lifecycle itself go through
     * applyReview (the workflow's activity), which this gate never touches.
     */
    @Transactional
    public Optional<Case> updateCase(UUID id, Map<String, Object> metadata, String actor, Channel channel) {
        Optional<CaseEntity> found = cases.findById(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CaseEntity entity = found.get();
        if (!CaseState.fromWire(entity.getState()).isOpen()) {
            throw new CaseClosedException();
        }
        if (metadata != null) {
            entity.setMetadata(toJson(metadata));
        }
        entity.setUpdatedAt(now());
        Case updated = toCase(cases.save(entity));

        writeLog(id, "updated", actor, channel, "Updated by " + actor, Map.of());
        return Optional.of(updated);
    }

    /**
     * Set a case's state and its review together. This is the write the
     * processCase workflow applies via its activity once a verdict lands; the
     * workflow logs the transition separately.
     */
    @Transactional
    public void applyReview(UUID caseId, CaseState state, Review review) {
        cases.applyReview(caseId, state.toString(), review == null ? null : toJson(review), now());
    }

    /**
     * The SLA ran out with nobody reviewing. No review is written, because none
     * happened — the absence is the finding.
     */
    @Transactional
    public void markLapsed(UUID caseId) {
        cases.updateState(caseId, CaseState.LAPSED.toString(), now());
    }

    /**
     * Record a verdict (the agent's or a human's) as a `reviewed` audit row. Does
     * NOT write case state — that flows through the workflow signal.
     */
    public void recordReview(
            UUID caseId, Review review, String actor, Channel channel) {
        writeLog(
                caseId,
                "reviewed",
                actor,
                channel,
                review.rationale(),
                Map.of(
                        "action", review.action().toString(),
                        "priority", String.valueOf(review.priority())));
    }

    /** Generic audit-log write. `actor` is a raw string: a human's id, or 'system'. */
    public void logActivity(
            UUID caseId, String action, String actor, Channel channel, String message, Map<String, Object> details) {
        writeLog(caseId, action, actor, channel, message, details == null ? Map.of() : details);
    }

    /**
     * Unwind an opening whose workflow never started: the usage row and the case
     * row go together, so a Temporal blip + retry cannot burn the allowance on
     * cases nobody can review.
     */
    @Transactional
    public void discardCase(UUID id) {
        usage.discard(id);
        logs.deleteByCaseId(id);
        cases.deleteById(id);
    }

    /** Permanently delete a case and its logs (logs FK-reference cases). */
    @Transactional
    public boolean deleteCase(UUID id) {
        if (!cases.existsById(id)) {
            return false;
        }
        logs.deleteByCaseId(id);
        cases.deleteById(id);
        return true;
    }

    public Optional<Case> getCase(UUID id) {
        return cases.findById(id).map(this::toCase);
    }

    public Optional<Case> findByAnvilItemId(String anvilItemId) {
        return cases.findByAnvilItemId(anvilItemId).map(this::toCase);
    }

    /** List cases, optionally filtered by state. Filtering and limiting happen in SQL. */
    public List<Case> listCases(CaseState state, Integer limit, Order order) {
        Sort sort = Sort.by(Sort.Direction.DESC, order == Order.UPDATED ? "updatedAt" : "createdAt");
        PageRequest page = PageRequest.of(0, limit == null ? DEFAULT_LIMIT : limit, sort);
        List<CaseEntity> found =
                state == null ? cases.findAllBy(page) : cases.findByState(state.toString(), page);
        return found.stream().map(this::toCase).toList();
    }

    /**
     * The open cases the daemon should look at next, nearest deadline first.
     * Ordering by the deadline rather than by age is what makes a bounded sweep
     * safe: if the agent can only get through ten cases a tick, they should be
     * the ten closest to lapsing.
     */
    public List<Case> openCasesBySlaDueAt(int limit) {
        return cases.findOpenBySlaDueAt(OPEN_STATES, limit).stream()
                .map(this::toCase)
                .toList();
    }

    /** A case's audit log, oldest-first (the case detail timeline). */
    public List<AuditLogEntry> getCaseLogs(UUID caseId) {
        return logs.findByCaseIdOrderByTimestampAsc(caseId).stream()
                .map(this::toLog)
                .toList();
    }

    /** Aggregate counts, computed in SQL rather than by fetching every row. */
    public Stats stats() {
        String watching = CaseState.WATCHING.toString();
        OffsetDateTime lastReviewed = logs.findFirstByActionOrderByTimestampDesc("reviewed")
                .map(LogEntity::getTimestamp)
                .orElse(null);
        return new Stats(
                (int) cases.countByState(watching),
                (int) cases.countByStateNot(watching),
                (int) cases.countOverdue(OPEN_STATES, now()),
                lastReviewed);
    }

    /** The most recent `reviewed` rows joined to their case title. */
    public List<ReviewedCase> recentReviews(int limit) {
        return logs.recentReviews(PageRequest.of(0, limit)).stream()
                .map(row -> new ReviewedCase(row.caseId(), row.title(), row.message(), fromJson(row.details()), row.at()))
                .toList();
    }

    /**
     * Attach a reasoning trace to a case's latest `reviewed` row (teaching aid).
     * No-op if the agent never recorded a review for this case.
     */
    @Transactional
    public void attachReviewTrace(UUID caseId, Object trace) {
        logs.mergeDetailsIntoLatestReview(caseId, toJson(Map.of("trace", trace)));
    }

    private void writeLog(
            UUID caseId, String action, String actor, Channel channel, String message, Map<String, Object> details) {
        logs.save(new LogEntity(
                UUID.randomUUID(),
                caseId,
                now(),
                action,
                actor,
                channel.toString(),
                message == null ? "" : message,
                toJson(details)));
    }

    private Case toCase(CaseEntity e) {
        return new Case(
                e.getId(),
                e.getAnvilItemId(),
                e.getTitle(),
                CaseState.fromWire(e.getState()),
                e.getAnvilStatus(),
                readJson(e.getAnvilDecision(), AnvilDecision.class),
                readJson(e.getReview(), Review.class),
                e.getSlaDueAt(),
                fromJson(e.getMetadata()),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    private AuditLogEntry toLog(LogEntity e) {
        return new AuditLogEntry(
                e.getId(),
                e.getCaseId(),
                e.getTimestamp(),
                e.getAction(),
                e.getActor(),
                Channel.fromWire(e.getChannel()),
                e.getMessage(),
                fromJson(e.getDetails()));
    }

    private String toJson(Object value) {
        return json.writeValueAsString(value);
    }

    private <T> T readJson(String value, Class<T> type) {
        return value == null ? null : json.readValue(value, type);
    }

    private Map<String, Object> fromJson(String value) {
        if (value == null) {
            return Map.of();
        }
        return json.readValue(value, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
