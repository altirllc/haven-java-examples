package haven.loom.workflows;

import haven.loom.contracts.CaseActivities;
import haven.loom.contracts.ProcessCaseWorkflow;
import haven.loom.domain.AuditLogEntry;
import haven.loom.domain.Channel;
import haven.loom.domain.ReviewAction;
import haven.loom.domain.ReviewSignal;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Waits for a verdict until the SLA runs out, then applies whichever came first.
 *
 * Workflow code — no IO, no clock, no randomness. The determinism fence means
 * the cases service is not even on this classpath; every write goes through an
 * activity stub.
 */
public class ProcessCaseWorkflowImpl implements ProcessCaseWorkflow {

    private final CaseActivities activities = Workflow.newActivityStub(
            CaseActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    private ReviewSignal pending;

    @Override
    public void processCase(UUID caseId, Duration sla) {
        activities.logActivity(
                caseId,
                "awaiting",
                AuditLogEntry.ACTOR_SYSTEM,
                Channel.SYSTEM,
                "Watching for a verdict (agent or human)",
                Map.of());

        // The deadline is fixed ONCE, at the start. Recomputing it per iteration
        // would mean every dispute silently grants a fresh SLA, and a case the
        // agent keeps disputing could never lapse — which is precisely the
        // failure loom exists to catch. Workflow.currentTimeMillis is the
        // replay-safe clock; System.currentTimeMillis would corrupt on replay.
        long deadline = Workflow.currentTimeMillis() + sla.toMillis();

        // Apply verdicts until a TERMINAL one lands. `dispute` is not terminal:
        // loom has told Anvil it disagrees and keeps watching, with the ORIGINAL
        // deadline still running against it.
        while (true) {
            Duration remaining = Duration.ofMillis(Math.max(0, deadline - Workflow.currentTimeMillis()));
            boolean received = Workflow.await(remaining, () -> pending != null);

            if (!received) {
                activities.markLapsed(caseId);
                activities.logActivity(
                        caseId,
                        "lapsed",
                        AuditLogEntry.ACTOR_SYSTEM,
                        Channel.SYSTEM,
                        "SLA elapsed with no verdict",
                        Map.of());
                // The most important notification loom sends: a case ran out of
                // time and NOBODY looked — neither Anvil's triager, nor loom,
                // nor a person. Nothing else will ever raise it.
                notify(caseId, "lapsed");
                return;
            }

            ReviewSignal signal = pending;
            pending = null;

            activities.applyReview(signal);
            activities.logActivity(
                    caseId,
                    signal.action().toString(),
                    signal.actor(),
                    signal.channel(),
                    "Case " + signal.action().resultingState()
                            + (signal.priority() == null ? "" : " (priority: " + signal.priority() + ")"),
                    details(signal));

            // A dispute is loom telling Anvil it got this wrong, so Anvil has
            // to hear about it. Done here rather than inside applyReview so the
            // outbound call gets its own retries and its own line in the
            // workflow history — and so a failure to reach Anvil cannot roll
            // back loom's own verdict, which stands either way.
            if (signal.action() == ReviewAction.DISPUTE) {
                try {
                    activities.pushBackToAnvil(caseId);
                } catch (RuntimeException unreachable) {
                    // The dispute is recorded regardless. What the case timeline
                    // must not do is imply Anvil was told when it was not.
                    activities.logActivity(
                            caseId,
                            "push-back-failed",
                            AuditLogEntry.ACTOR_SYSTEM,
                            Channel.SYSTEM,
                            "Disputed, but Anvil could not be told",
                            Map.of("error", String.valueOf(unreachable.getMessage())));
                }
            }

            // Escalating means loom is handing this to a person. Telling them
            // is the whole of it — an escalation nobody hears about is a state
            // change, not an escalation.
            if (signal.action() == ReviewAction.ESCALATE) {
                notify(caseId, "escalated");
            }

            if (signal.action().terminal()) {
                return;
            }
        }
    }

    /**
     * Notify, and never let the attempt take the workflow down.
     *
     * A failed notification is itself news — it means a person who should have
     * been told was not — so it goes in the timeline rather than into a log
     * nobody reads.
     */
    private void notify(UUID caseId, String reason) {
        try {
            activities.notifyHumans(caseId, reason);
        } catch (RuntimeException failed) {
            activities.logActivity(
                    caseId,
                    "notify-failed",
                    AuditLogEntry.ACTOR_SYSTEM,
                    Channel.SYSTEM,
                    "Nobody could be told about this case",
                    Map.of("reason", reason, "error", String.valueOf(failed.getMessage())));
        }
    }

    @Override
    public void review(ReviewSignal signal) {
        pending = signal;
    }

    private static Map<String, Object> details(ReviewSignal signal) {
        if (signal.rationale() == null && signal.priority() == null) {
            return Map.of();
        }
        if (signal.rationale() == null) {
            return Map.of("priority", signal.priority().toString());
        }
        if (signal.priority() == null) {
            return Map.of("rationale", signal.rationale());
        }
        return Map.of("rationale", signal.rationale(), "priority", signal.priority().toString());
    }
}
