package haven.loom.agent;

import haven.loom.domain.AnvilDecision;
import haven.loom.domain.Case;
import haven.loom.domain.CaseState;
import haven.loom.domain.Channel;
import haven.loom.domain.Priority;
import haven.loom.domain.Review;
import haven.loom.domain.ReviewAction;
import haven.loom.orchestration.CaseOrchestrator;
import haven.loom.persistence.CaseService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The verbs the agent can call. Three read, one act.
 *
 * Every read and write goes through the cases service; these tools own only
 * their shape and the workflow signal, never SQL.
 */
@Component
public class CaseTools {

    private final CaseService cases;
    private final CaseOrchestrator orchestrator;
    private final AgentDaemonProperties daemon;

    public CaseTools(CaseService cases, CaseOrchestrator orchestrator, AgentDaemonProperties daemon) {
        this.cases = cases;
        this.orchestrator = orchestrator;
        this.daemon = daemon;
    }

    /**
     * What the agent sees of a case. Anvil's decision is spelled out rather than
     * summarised: the agent's whole job is to have an opinion about it, so
     * hiding it behind a status string would leave nothing to reason over.
     */
    public record CaseView(
            String id,
            String anvilItemId,
            String title,
            String state,
            String anvilStatus,
            String anvilAction,
            String anvilRationale,
            String slaDueAt) {}

    public record CaseSummary(String id, String title, String state, String anvilStatus, String priority) {}

    public record AgentStatus(String interval, String lastActionAt, int watching, int reviewed, int overdue) {}

    public record Reviewed(boolean ok) {}

    @Tool(
            name = "get-case",
            description = "Look up a single case by its id and return what Anvil decided about the item, the case's "
                    + "current state, and when its review deadline falls. Use this when you need the full detail of "
                    + "one specific case — for example, before reviewing it or answering a question about it.")
    public CaseView getCase(@ToolParam(description = "The case's id.") String caseId) {
        return cases.getCase(UUID.fromString(caseId)).map(CaseTools::view).orElse(null);
    }

    @Tool(
            name = "list-cases",
            description = "List supervised cases, most-recently-updated first, optionally filtered by state. Each "
                    + "returned case includes the priority of loom's own review, so for priority questions read them "
                    + "off the results — there is no priority filter argument.")
    public List<CaseSummary> listCases(
            @ToolParam(
                            required = false,
                            description = "Filter by state: watching, agreed, disputed, escalated, lapsed.")
                    String state,
            @ToolParam(required = false, description = "How many to return, 1-50. Defaults to 20.") Integer limit) {
        CaseState filter = state == null || state.isBlank() ? null : CaseState.fromWire(state);
        int capped = limit == null ? 20 : Math.clamp(limit, 1, 50);
        return cases.listCases(filter, capped, CaseService.Order.UPDATED).stream()
                .map(theCase -> new CaseSummary(
                        theCase.id().toString(),
                        theCase.title(),
                        theCase.state().toString(),
                        theCase.anvilStatus(),
                        theCase.priority() == null ? null : theCase.priority().toString()))
                .toList();
    }

    @Tool(
            name = "agent-status",
            description = "Report the agent's status: its sweep interval, when it last reviewed a case, how many "
                    + "cases are still being watched versus reviewed, and how many are past their deadline. Use this "
                    + "for questions about when it runs, how often, or how much it has done.")
    public AgentStatus agentStatus() {
        CaseService.Stats stats = cases.stats();
        return new AgentStatus(
                daemon.display(),
                stats.lastReviewedAt() == null ? null : stats.lastReviewedAt().toString(),
                stats.watching(),
                stats.reviewed(),
                stats.overdue());
    }

    /**
     * The only tool that changes a case — and it does not write state. It records
     * the verdict, then SIGNALS the case's workflow exactly as a human's review
     * does. If the workflow already resolved, the verdict cannot land but the
     * reasoning is still logged.
     */
    @Tool(
            name = "review-case",
            description = "Review a case: agree with Anvil's decision, dispute it, or escalate to a human. Set a "
                    + "priority and give a one-line rationale. This sends the verdict to the case for application — "
                    + "the only tool that changes a case. Call it whenever you have reached a view.")
    public Reviewed reviewCase(
            @ToolParam(description = "The case's id.") String caseId,
            @ToolParam(description = "One of: agree, dispute, escalate.") String action,
            @ToolParam(description = "One of: low, medium, high.") String priority,
            @ToolParam(description = "One sentence explaining the verdict.") String rationale) {
        UUID id = UUID.fromString(caseId);
        ReviewAction verb = ReviewAction.fromWire(action);
        Priority level = Priority.fromWire(priority);

        cases.recordReview(
                id,
                new Review(verb, level, rationale, OffsetDateTime.now(ZoneOffset.UTC)),
                "agent",
                Channel.AGENT);
        boolean delivered = orchestrator.signalReview(id, verb, level, rationale, "agent", Channel.AGENT);
        return new Reviewed(delivered);
    }

    private static CaseView view(Case theCase) {
        AnvilDecision decision = theCase.anvilDecision();
        return new CaseView(
                theCase.id().toString(),
                theCase.anvilItemId(),
                theCase.title(),
                theCase.state().toString(),
                theCase.anvilStatus(),
                decision == null ? null : decision.action(),
                decision == null ? null : decision.rationale(),
                theCase.slaDueAt() == null ? null : theCase.slaDueAt().toString());
    }

    /** Display-only view of a case, for the sweep prompt. */
    public static String describe(Case theCase) {
        AnvilDecision decision = theCase.anvilDecision();
        return "Case id: " + theCase.id()
                + "\nAnvil item: " + theCase.anvilItemId()
                + "\nTitle: " + theCase.title()
                + "\nAnvil status: " + (theCase.anvilStatus() == null ? "(none yet)" : theCase.anvilStatus())
                + "\nAnvil decided: "
                + (decision == null
                        ? "(nothing yet - there is no decision to judge)"
                        : decision.action() + " - " + (decision.rationale() == null ? "(no reason given)" : decision.rationale()))
                + "\nYour previous verdict: "
                + (theCase.review() == null
                        ? "(none)"
                        : theCase.review().action() + " - " + theCase.review().rationale())
                + "\nDeadline: " + remaining(theCase.slaDueAt());
    }

    /**
     * How long is left, in words.
     *
     * Deliberately not the raw timestamp: date arithmetic is exactly the kind of
     * thing a language model gets quietly wrong, and "little time remains" is the
     * condition the mandate tells it to escalate on. Computing it here makes that
     * judgement a reading rather than a calculation.
     */
    private static String remaining(OffsetDateTime dueAt) {
        if (dueAt == null) {
            return "none set";
        }
        Duration left = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), dueAt);
        if (left.isNegative()) {
            return "PASSED " + human(left.negated()) + " ago";
        }
        return "in " + human(left);
    }

    private static String human(Duration duration) {
        long hours = duration.toHours();
        if (hours >= 24) {
            return duration.toDays() + "d " + (hours % 24) + "h";
        }
        if (hours > 0) {
            return hours + "h " + duration.toMinutesPart() + "m";
        }
        return Math.max(1, duration.toMinutes()) + "m";
    }
}
