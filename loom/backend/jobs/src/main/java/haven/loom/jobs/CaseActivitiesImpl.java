package haven.loom.jobs;

import haven.loom.contracts.CaseActivities;
import haven.loom.domain.AuditLogEntry;
import haven.loom.domain.Channel;
import haven.loom.domain.Review;
import haven.loom.domain.ReviewSignal;
import haven.loom.domain.Case;
import haven.loom.jobs.dispatch.Dispatch;
import haven.loom.persistence.CaseService;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The case writes the workflow asks for.
 *
 * Activities run on ordinary worker threads outside Temporal's deterministic
 * scheduler, so Spring injection and Postgres access are fine here — which is
 * exactly why the workflow delegates every write to this side.
 *
 * Thin by design: the workflow-applied state write and the audit log share one
 * implementation with every other surface, in the cases service.
 */
@Component
public class CaseActivitiesImpl implements CaseActivities {

    private static final Logger log = LoggerFactory.getLogger(CaseActivitiesImpl.class);

    private final CaseService cases;
    private final AnvilClient anvil;
    /**
     * ObjectProvider because Dispatch is conditional: a tenant without a
     * notifications stack has no such bean, and that is a supported shape rather
     * than a broken wiring.
     */
    private final ObjectProvider<Dispatch> dispatch;

    public CaseActivitiesImpl(CaseService cases, AnvilClient anvil, ObjectProvider<Dispatch> dispatch) {
        this.cases = cases;
        this.anvil = anvil;
        this.dispatch = dispatch;
    }

    @Override
    public void applyReview(ReviewSignal signal) {
        // The Review is assembled HERE rather than in the workflow: it carries a
        // timestamp, and workflow code must not construct clock-derived values.
        // The signal carries the moment as a String precisely so this side can
        // turn it back into one.
        Review review = new Review(
                signal.action(),
                signal.priority(),
                signal.rationale(),
                OffsetDateTime.parse(signal.actedAt()));
        cases.applyReview(signal.caseId(), signal.action().resultingState(), review);
    }

    @Override
    public void pushBackToAnvil(UUID caseId) {
        Optional<Case> found = cases.getCase(caseId);
        if (found.isEmpty()) {
            // Deleted between the verdict and this call. Nothing to push back to,
            // and nowhere to write the audit row either.
            log.warn("Case {} vanished before its dispute could be pushed back", caseId);
            return;
        }
        Case theCase = found.get();
        String rationale = theCase.review() == null ? "" : theCase.review().rationale();

        AnvilClient.PushBack result;
        try {
            result = anvil.flagItem(theCase.anvilItemId(), "loom disputes this decision: " + rationale);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Push-back interrupted", interrupted);
        } catch (Exception unreachable) {
            // Thrown so the activity retries and, if it keeps failing, the
            // workflow can say so in the timeline.
            throw new IllegalStateException(
                    "Could not ask Anvil to flag " + theCase.anvilItemId() + ": " + unreachable.getMessage(),
                    unreachable);
        }

        cases.logActivity(
                caseId,
                "pushed-back",
                AuditLogEntry.ACTOR_SYSTEM,
                Channel.SYSTEM,
                result.detail(),
                Map.of("anvilItemId", theCase.anvilItemId(), "delivered", result.delivered()));
    }

    @Override
    public void notifyHumans(UUID caseId, String reason) {
        Optional<Case> found = cases.getCase(caseId);
        if (found.isEmpty()) {
            log.warn("Case {} vanished before anyone could be told", caseId);
            return;
        }
        Case theCase = found.get();

        Dispatch notifier = dispatch.getIfAvailable();
        String blocked = notifier == null ? "dispatch.enabled is false" : notifier.unavailable();
        if (blocked != null) {
            // Not thrown: there is nothing to retry, and a tenant that has not
            // set notifications up is not failing. What it must not be is quiet.
            cases.logActivity(
                    caseId,
                    "notify-skipped",
                    AuditLogEntry.ACTOR_SYSTEM,
                    Channel.SYSTEM,
                    "Nobody was told - " + blocked,
                    Map.of("reason", reason));
            return;
        }

        notifier.send(new Dispatch.Notification(
                caseId,
                theCase.anvilItemId(),
                theCase.title(),
                reason,
                theCase.review() == null ? null : theCase.review().rationale()));

        cases.logActivity(
                caseId,
                "notified",
                AuditLogEntry.ACTOR_SYSTEM,
                Channel.SYSTEM,
                "A notification was raised for a human",
                Map.of("reason", reason));
    }

    @Override
    public void markLapsed(UUID caseId) {
        cases.markLapsed(caseId);
    }

    @Override
    public void logActivity(
            UUID caseId, String action, String actor, Channel channel, String message, Map<String, Object> details) {
        cases.logActivity(caseId, action, actor, channel, message, details);
    }
}
