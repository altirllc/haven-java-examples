package haven.loom.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import haven.loom.domain.AnvilDecision;
import haven.loom.domain.Case;
import haven.loom.domain.CaseState;
import haven.loom.domain.Channel;
import haven.loom.domain.Priority;
import haven.loom.domain.Review;
import haven.loom.domain.ReviewAction;
import haven.loom.persistence.jpa.CaseRepository;
import haven.loom.persistence.jpa.LogRepository;
import haven.loom.persistence.jpa.UsageEventRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Exercises the cases service against real Postgres through the full Boot
 * wiring: Liquibase migrates, Hibernate validates the entities against the
 * migrated schema (the drift gate), and the service runs on the real
 * repositories. jsonb, the UUID keys, and the server-side merges all behave
 * differently on H2, so an in-memory database would prove nothing here.
 */
@SpringBootTest
@Testcontainers
class CaseServiceTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    // Also exercises MongoConnectionCheck: the context refuses to start unless
    // the boot-time ping reaches a live Mongo.
    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    private static final Duration SLA = Duration.ofHours(24);

    @Autowired
    private CaseService service;

    @Autowired
    private UsageService usage;

    @Autowired
    private CaseRepository cases;

    @Autowired
    private LogRepository logs;

    @Autowired
    private UsageEventRepository usageEvents;

    @BeforeEach
    void reset() {
        logs.deleteAllInBatch();
        cases.deleteAllInBatch();
        usageEvents.deleteAllInBatch();
    }

    private Case open(String anvilItemId, String title) {
        return service.openCase(
                UUID.randomUUID(),
                anvilItemId,
                title,
                "draft",
                SLA,
                Map.of("source", "anvil"),
                "dev@acme",
                Channel.API);
    }

    @Test
    void opensCaseAsWatchingAndLogsIt() {
        Case opened = open("anvil-1", "Onboarding Checklist");

        assertThat(opened.state()).isEqualTo(CaseState.WATCHING);
        assertThat(opened.anvilItemId()).isEqualTo("anvil-1");
        assertThat(opened.review()).as("nothing has been reviewed yet").isNull();
        assertThat(opened.anvilDecision()).as("Anvil has not decided yet").isNull();
        assertThat(opened.slaDueAt()).isAfter(opened.createdAt());
        assertThat(service.getCase(opened.id())).contains(opened);
        assertThat(service.getCaseLogs(opened.id())).singleElement().satisfies(log -> {
            assertThat(log.action()).isEqualTo("opened");
            assertThat(log.actor()).isEqualTo("dev@acme");
            assertThat(log.channel()).isEqualTo(Channel.API);
        });
    }

    @Test
    void openingTheSameAnvilItemTwiceReturnsTheSameCase() {
        // Ingest re-reads the same items every tick. This is the invariant that
        // stops it from opening a second case — and from metering a second unit —
        // on every pass.
        Case first = open("anvil-dup", "Seen twice");
        Case second = open("anvil-dup", "Seen twice");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(service.listCases(null, 50, CaseService.Order.CREATED)).hasSize(1);
        assertThat(usage.used()).isEqualTo(1);
    }

    @Test
    void listFiltersByStateInSql() {
        Case watching = open("anvil-a", "Still watching");
        Case agreed = open("anvil-b", "Will be agreed");
        service.applyReview(agreed.id(), CaseState.AGREED, review(ReviewAction.AGREE, Priority.LOW));

        assertThat(service.listCases(CaseState.WATCHING, null, CaseService.Order.CREATED))
                .extracting(Case::id)
                .containsExactly(watching.id());
        assertThat(service.listCases(null, null, CaseService.Order.CREATED)).hasSize(2);
    }

    @Test
    void listRespectsLimit() {
        open("anvil-1", "one");
        open("anvil-2", "two");
        open("anvil-3", "three");

        assertThat(service.listCases(null, 2, CaseService.Order.CREATED)).hasSize(2);
    }

    @Test
    void applyReviewWritesStateAndVerdictTogether() {
        Case opened = open("anvil-nda", "Vendor NDA");

        service.applyReview(opened.id(), CaseState.DISPUTED, review(ReviewAction.DISPUTE, Priority.HIGH));

        Case updated = service.getCase(opened.id()).orElseThrow();
        assertThat(updated.state()).isEqualTo(CaseState.DISPUTED);
        assertThat(updated.review().action()).isEqualTo(ReviewAction.DISPUTE);
        assertThat(updated.priority()).isEqualTo(Priority.HIGH);
        assertThat(updated.metadata())
                .as("the review write must not clobber metadata")
                .containsEntry("source", "anvil");
    }

    @Test
    void syncFromAnvilRecordsTheirDecisionWithoutTouchingOurs() {
        Case opened = open("anvil-budget", "Q1 Budget");
        service.applyReview(opened.id(), CaseState.DISPUTED, review(ReviewAction.DISPUTE, Priority.HIGH));

        service.syncFromAnvil(
                opened.id(),
                "Q1 Budget (revised)",
                "approved",
                new AnvilDecision("approve", "medium", "Routine and well-formed.", OffsetDateTime.now(ZoneOffset.UTC)),
                "system",
                Channel.SYSTEM);

        Case synced = service.getCase(opened.id()).orElseThrow();
        assertThat(synced.title()).isEqualTo("Q1 Budget (revised)");
        assertThat(synced.anvilStatus()).isEqualTo("approved");
        assertThat(synced.anvilDecision().action()).isEqualTo("approve");
        assertThat(synced.review().action())
                .as("Anvil's decision must never overwrite loom's verdict")
                .isEqualTo(ReviewAction.DISPUTE);
        assertThat(synced.state()).isEqualTo(CaseState.DISPUTED);
        assertThat(service.getCaseLogs(opened.id()))
                .filteredOn(log -> log.action().equals("anvil-decided"))
                .hasSize(1);
    }

    @Test
    void syncFromAnvilLogsADecisionOnlyWhenItChanged() {
        Case opened = open("anvil-repeat", "Unchanged");
        AnvilDecision decision =
                new AnvilDecision("approve", "low", "Fine.", OffsetDateTime.now(ZoneOffset.UTC).withNano(0));

        service.syncFromAnvil(opened.id(), "Unchanged", "approved", decision, "system", Channel.SYSTEM);
        service.syncFromAnvil(opened.id(), "Unchanged", "approved", decision, "system", Channel.SYSTEM);

        assertThat(service.getCaseLogs(opened.id()))
                .filteredOn(log -> log.action().equals("anvil-decided"))
                .as("ingest runs on a timer; an unchanged decision must not fill the timeline")
                .hasSize(1);
    }

    @Test
    void recordReviewWritesAuditRowButNotState() {
        Case opened = open("anvil-q1", "Q1 Budget");

        service.recordReview(
                opened.id(), review(ReviewAction.AGREE, Priority.MEDIUM), "agent", Channel.AGENT);

        assertThat(service.getCase(opened.id()).orElseThrow().state())
                .as("recordReview must not write state — that flows through the workflow signal")
                .isEqualTo(CaseState.WATCHING);
        assertThat(service.getCaseLogs(opened.id()))
                .filteredOn(log -> log.action().equals("reviewed"))
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.message()).isEqualTo("Anvil's call looks right.");
                    assertThat(log.details()).containsEntry("action", "agree").containsEntry("priority", "medium");
                });
    }

    @Test
    void markLapsedClosesTheCaseWithNoVerdict() {
        Case opened = open("anvil-forgotten", "Nobody looked");

        service.markLapsed(opened.id());

        Case lapsed = service.getCase(opened.id()).orElseThrow();
        assertThat(lapsed.state()).isEqualTo(CaseState.LAPSED);
        assertThat(lapsed.review()).as("no review happened — the absence is the finding").isNull();
    }

    @Test
    void openCasesComeBackNearestDeadlineFirst() {
        Case urgent = service.openCase(
                UUID.randomUUID(), "anvil-urgent", "Due soon", "draft", Duration.ofMinutes(5), Map.of(), "dev", Channel.API);
        Case relaxed = service.openCase(
                UUID.randomUUID(), "anvil-relaxed", "Due later", "draft", Duration.ofDays(7), Map.of(), "dev", Channel.API);

        assertThat(service.openCasesBySlaDueAt(10))
                .extracting(Case::id)
                .as("a bounded sweep must spend its budget on what is closest to lapsing")
                .containsExactly(urgent.id(), relaxed.id());
    }

    @Test
    void closedCasesAreNotSweptAgain() {
        Case agreed = open("anvil-done", "Settled");
        service.applyReview(agreed.id(), CaseState.AGREED, review(ReviewAction.AGREE, Priority.LOW));
        Case disputed = open("anvil-open", "Still arguing");
        service.applyReview(disputed.id(), CaseState.DISPUTED, review(ReviewAction.DISPUTE, Priority.HIGH));

        assertThat(service.openCasesBySlaDueAt(10))
                .extracting(Case::id)
                .as("a dispute stays open on purpose; an agreement does not")
                .containsExactly(disputed.id());
    }

    @Test
    void statsCountsInSql() {
        open("anvil-1", "watching");
        Case reviewed = open("anvil-2", "reviewed");
        service.applyReview(reviewed.id(), CaseState.AGREED, review(ReviewAction.AGREE, Priority.HIGH));
        service.recordReview(reviewed.id(), review(ReviewAction.AGREE, Priority.HIGH), "agent", Channel.AGENT);

        CaseService.Stats stats = service.stats();

        assertThat(stats.watching()).isEqualTo(1);
        assertThat(stats.reviewed()).isEqualTo(1);
        assertThat(stats.lastReviewedAt()).isNotNull();
    }

    @Test
    void statsCountsOverdueOpenCasesOnly() {
        // A deadline already in the past, so the count is answerable now rather
        // than after a wait.
        Case overdue = service.openCase(
                UUID.randomUUID(), "anvil-late", "Late", "draft", Duration.ofHours(-1), Map.of(), "dev", Channel.API);
        open("anvil-fine", "Fine");

        assertThat(service.stats().overdue()).isEqualTo(1);

        service.applyReview(overdue.id(), CaseState.AGREED, review(ReviewAction.AGREE, Priority.LOW));
        assertThat(service.stats().overdue())
                .as("a closed case cannot be overdue — there is nothing left to be late for")
                .isZero();
    }

    @Test
    void recentReviewsJoinsCaseTitle() {
        Case opened = open("anvil-sec", "Security Playbook");
        service.recordReview(opened.id(), review(ReviewAction.AGREE, Priority.HIGH), "agent", Channel.AGENT);

        assertThat(service.recentReviews(20)).singleElement().satisfies(reviewed -> {
            assertThat(reviewed.title()).isEqualTo("Security Playbook");
            assertThat(reviewed.rationale()).isEqualTo("Anvil's call looks right.");
            assertThat(reviewed.details()).containsEntry("action", "agree");
        });
    }

    @Test
    void attachReviewTracePatchesLatestReviewedRow() {
        Case opened = open("anvil-rate", "Rate Limit Policy");
        service.recordReview(opened.id(), review(ReviewAction.AGREE, Priority.LOW), "agent", Channel.AGENT);

        service.attachReviewTrace(opened.id(), Map.of("prompt", "review this", "ms", 1234));

        assertThat(service.recentReviews(1)).singleElement().satisfies(reviewed -> {
            assertThat(reviewed.details()).containsKey("trace");
            assertThat(reviewed.details()).containsEntry("action", "agree");
        });
    }

    @Test
    void attachReviewTraceIsNoOpWhenAgentNeverReviewed() {
        Case opened = open("anvil-none", "Never reviewed");

        service.attachReviewTrace(opened.id(), Map.of("prompt", "x"));

        assertThat(service.recentReviews(10)).isEmpty();
    }

    @Test
    void openingConsumesOneUnitPerCase() {
        open("anvil-1", "one");
        open("anvil-2", "two");

        assertThat(usage.used()).isEqualTo(2);
    }

    @Test
    void freePlanRefusesOpeningBeyondTheAllowance() {
        for (int i = 0; i < 10; i++) {
            open("anvil-" + i, "case " + i);
        }

        assertThatThrownBy(() -> open("anvil-overflow", "one too many"))
                .isInstanceOf(PlanLimitReachedException.class);
        assertThat(service.listCases(null, 50, CaseService.Order.CREATED))
                .as("a refusal writes nothing")
                .hasSize(10);
        assertThat(usage.used()).isEqualTo(10);
    }

    @Test
    void reviewsConsumeNothing() {
        Case opened = open("anvil-review", "Review me");
        service.recordReview(opened.id(), review(ReviewAction.AGREE, Priority.LOW), "agent", Channel.AGENT);
        service.applyReview(opened.id(), CaseState.AGREED, review(ReviewAction.AGREE, Priority.LOW));

        assertThat(usage.used())
                .as("the unit is a case supervised, not a database write")
                .isEqualTo(1);
    }

    @Test
    void discardCaseUnwindsTheConsumedUnit() {
        Case opened = open("anvil-ghost", "Ghost case");

        service.discardCase(opened.id());

        assertThat(service.getCase(opened.id())).isEmpty();
        assertThat(service.getCaseLogs(opened.id())).isEmpty();
        assertThat(usage.used()).isZero();
    }

    @Test
    void updatingAClosedCaseIsRefused() {
        Case opened = open("anvil-closed", "Closed");
        service.applyReview(opened.id(), CaseState.AGREED, review(ReviewAction.AGREE, Priority.LOW));

        assertThatThrownBy(() -> service.updateCase(opened.id(), Map.of("note", "late"), "dev", Channel.API))
                .isInstanceOf(CaseClosedException.class);
    }

    @Test
    void deleteRemovesCaseAndItsLogs() {
        Case opened = open("anvil-delete", "Delete me");

        assertThat(service.deleteCase(opened.id())).isTrue();
        assertThat(service.getCase(opened.id())).isEmpty();
        assertThat(service.getCaseLogs(opened.id())).isEmpty();
        assertThat(service.deleteCase(opened.id())).isFalse();
    }

    @Nested
    @SpringBootTest(properties = "plan.name=paid")
    class PaidPlan {

        @Test
        void paidPlanContinuesPastTheAllowance() {
            for (int i = 0; i < 11; i++) {
                open("anvil-paid-" + i, "case " + i);
            }

            assertThat(usage.used()).isEqualTo(11);
        }
    }

    private static Review review(ReviewAction action, Priority priority) {
        return new Review(action, priority, "Anvil's call looks right.", OffsetDateTime.now(ZoneOffset.UTC));
    }
}
