package haven.loom.api.mcp;

import haven.loom.domain.AuditLogEntry;
import haven.loom.domain.Case;
import haven.loom.domain.CaseState;
import haven.loom.domain.Channel;
import haven.loom.domain.Priority;
import haven.loom.domain.Review;
import haven.loom.domain.ReviewAction;
import haven.loom.domain.Role;
import haven.loom.orchestration.CaseOrchestrator;
import haven.loom.persistence.CaseProperties;
import haven.loom.persistence.CaseService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * loom's tools over the Model Context Protocol — case reads plus the review
 * verbs (agree/dispute/escalate) for an authenticated tenant user driving their
 * own MCP client.
 *
 * Each tool is a thin adapter owning only the MCP shape; every table write goes
 * through the cases service, opening also starts the workflow, and the review
 * verbs signal it through the orchestrator — the identical path the REST routes
 * use — so persistence, the audit log and the valid-state set are defined once.
 *
 * Role-gated per request: tool beans are singletons, so the caller comes from
 * the security context — see McpCaller.
 */
@Component
public class CaseMcpTools {

    private static final Logger log = LoggerFactory.getLogger(CaseMcpTools.class);

    private final CaseService cases;
    private final CaseOrchestrator orchestrator;
    private final McpCaller caller;
    private final CaseProperties properties;

    public CaseMcpTools(
            CaseService cases, CaseOrchestrator orchestrator, McpCaller caller, CaseProperties properties) {
        this.cases = cases;
        this.orchestrator = orchestrator;
        this.caller = caller;
        this.properties = properties;
    }

    @Tool(name = "list_cases", description = "List supervised cases, most recent first.")
    public List<Case> listCases(
            @ToolParam(
                            required = false,
                            description = "Filter by state: watching, agreed, disputed, escalated, lapsed.")
                    String state,
            @ToolParam(required = false, description = "Max cases to return (default 20, max 50).") Integer limit) {
        caller.require(Role.VIEWER);
        CaseState filter = state == null || state.isBlank() ? null : CaseState.fromWire(state);
        int capped = limit == null ? 20 : Math.clamp(limit, 1, 50);
        return cases.listCases(filter, capped, CaseService.Order.CREATED);
    }

    @Tool(
            name = "get_case",
            description = "Get one case: what Anvil decided, loom's own verdict, and the review deadline.")
    public Case getCase(@ToolParam(description = "The case id.") String caseId) {
        caller.require(Role.VIEWER);
        return cases.getCase(UUID.fromString(caseId)).orElse(null);
    }

    @Tool(name = "get_case_log", description = "The audit timeline for one case, oldest first.")
    public List<AuditLogEntry> getCaseLog(@ToolParam(description = "The case id.") String caseId) {
        caller.require(Role.VIEWER);
        return cases.getCaseLogs(UUID.fromString(caseId));
    }

    /**
     * Idempotent on the Anvil item id, like the REST route: a second call returns
     * the case that already exists rather than opening a duplicate, and only a
     * genuinely new case starts a workflow.
     */
    @Tool(
            name = "open_case",
            description = "Start supervising an Anvil item. Returns the existing case if one is already open for it.")
    public Case openCase(
            @ToolParam(description = "The Anvil item id to supervise.") String anvilItemId,
            @ToolParam(required = false, description = "A title for the case; defaults to the Anvil item id.")
                    String title,
            @ToolParam(required = false, description = "Anvil's current status for the item.") String anvilStatus) {
        String actor = caller.require(Role.MEMBER);

        Optional<Case> existing = cases.findByAnvilItemId(anvilItemId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID id = UUID.randomUUID();
        Duration sla = properties.sla();
        Case opened = cases.openCase(
                id,
                anvilItemId,
                title == null || title.isBlank() ? anvilItemId : title,
                anvilStatus,
                sla,
                Map.of(),
                actor,
                Channel.MCP);
        try {
            orchestrator.startProcessCase(opened.id(), sla);
        } catch (RuntimeException temporalIsDown) {
            log.warn("Could not start the workflow for case {} — unwinding", opened.id(), temporalIsDown);
            cases.discardCase(opened.id());
            throw temporalIsDown;
        }
        return opened;
    }

    @Tool(name = "agree_case", description = "Agree with Anvil's decision on a case. Terminal.")
    public Map<String, Object> agreeCase(
            @ToolParam(description = "The case id.") String caseId,
            @ToolParam(description = "One of: low, medium, high.") String priority,
            @ToolParam(description = "One sentence explaining the verdict.") String rationale) {
        return review(caseId, ReviewAction.AGREE, priority, rationale);
    }

    @Tool(
            name = "dispute_case",
            description = "Dispute Anvil's decision on a case. The case stays open and its deadline keeps running.")
    public Map<String, Object> disputeCase(
            @ToolParam(description = "The case id.") String caseId,
            @ToolParam(description = "One of: low, medium, high.") String priority,
            @ToolParam(description = "One sentence explaining the verdict.") String rationale) {
        return review(caseId, ReviewAction.DISPUTE, priority, rationale);
    }

    @Tool(name = "escalate_case", description = "Escalate a case to a human. Terminal for loom.")
    public Map<String, Object> escalateCase(
            @ToolParam(description = "The case id.") String caseId,
            @ToolParam(description = "One of: low, medium, high.") String priority,
            @ToolParam(description = "One sentence explaining the verdict.") String rationale) {
        return review(caseId, ReviewAction.ESCALATE, priority, rationale);
    }

    /**
     * One path for every verdict. `delivered: false` means the case already
     * closed — the workflow is gone, so the verdict has nowhere to land. The
     * reasoning is still recorded, because a rejected verdict is still evidence
     * of what the caller thought.
     */
    private Map<String, Object> review(String caseId, ReviewAction action, String priority, String rationale) {
        String actor = caller.require(Role.MEMBER);
        UUID id = UUID.fromString(caseId);
        Priority level = priority == null || priority.isBlank() ? Priority.MEDIUM : Priority.fromWire(priority);

        cases.recordReview(
                id, new Review(action, level, rationale, OffsetDateTime.now()), actor, Channel.MCP);
        boolean delivered = orchestrator.signalReview(id, action, level, rationale, actor, Channel.MCP);
        return Map.of("delivered", delivered, "action", action.toString());
    }
}
