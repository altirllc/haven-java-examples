package haven.loom.api;

import haven.loom.agent.AgentDefinition;
import haven.loom.agent.ModelsGatewayProperties;
import haven.loom.agent.LoomAgent;
import haven.loom.domain.Role;
import haven.loom.persistence.CaseService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * The agent's read surface plus chat.
 *
 * /config is what the agent IS and never changes at runtime; /activity is what
 * it has DONE and is polled. Chat is a secondary lens onto the same agent,
 * independent of the autonomous daemon.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final LoomAgent agent;
    private final AgentDefinition definition;
    private final CaseService cases;
    private final ModelsGatewayProperties gateway;
    private final CurrentUser currentUser;

    public AgentController(
            LoomAgent agent,
            AgentDefinition definition,
            CaseService cases,
            ModelsGatewayProperties gateway,
            CurrentUser currentUser) {
        this.agent = agent;
        this.definition = definition;
        this.cases = cases;
        this.gateway = gateway;
        this.currentUser = currentUser;
    }

    public record ChatBody(String message, String threadId) {}

    /** Static agent definition — powers the teaching surface. Fetched once. */
    @GetMapping("/config")
    public Map<String, Object> config(HttpServletRequest request) {
        currentUser.require(request, Role.VIEWER);
        return Map.of(
                "models",
                Map.of(
                        "reasoning",
                        Map.of(
                                "provider", "Haven Models",
                                "model", gateway.chatModel()),
                        "embedding",
                        Map.of(
                                "provider", "Haven Models",
                                "model", gateway.embeddingModel())),
                "instructions",
                definition.instructions(),
                "tools",
                definition.toolSpecs(),
                "memory",
                definition.memory(),
                "trigger",
                Map.of(
                        "source", "Temporal daemon (long-lived loop)",
                        "workflowId", haven.loom.contracts.AgentWorkflow.WORKFLOW_ID,
                        "interval", definition.interval(),
                        "batch", definition.batchSize()));
    }

    /** Daemon status + recent reviews — polled by the history panel. */
    @GetMapping("/activity")
    public Map<String, Object> activity(HttpServletRequest request) {
        currentUser.require(request, Role.VIEWER);

        CaseService.Stats stats = cases.stats();
        List<CaseService.ReviewedCase> reviews = cases.recentReviews(20);

        Map<String, Object> status = new HashMap<>();
        status.put("interval", definition.interval());
        status.put("lastActionAt", stats.lastReviewedAt() == null ? null : stats.lastReviewedAt().toString());
        status.put("watching", stats.watching());
        status.put("reviewed", stats.reviewed());
        status.put("overdue", stats.overdue());

        List<Map<String, Object>> actions = reviews.stream()
                .map(reviewed -> {
                    Map<String, Object> action = new HashMap<>();
                    action.put("caseId", reviewed.caseId().toString());
                    action.put("title", reviewed.title());
                    action.put("action", reviewed.details().get("action"));
                    action.put("priority", reviewed.details().get("priority"));
                    action.put("rationale", reviewed.rationale());
                    action.put("at", reviewed.at().toString());
                    return action;
                })
                .<Map<String, Object>>toList();

        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        body.put("actions", actions);
        return body;
    }

    /**
     * Streams the reply as SSE in the shape the UI already parses with plain
     * fetch + ReadableStream:
     *   data: {"type":"text-delta","textDelta":"..."}
     *   data: {"type":"done"}
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(HttpServletRequest request, @RequestBody ChatBody body) {
        currentUser.require(request, Role.MEMBER);
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "message is required");
        }

        SseEmitter emitter = new SseEmitter(0L);
        Flux<String> stream = agent.chat(
                body.message().trim(), body.threadId() == null ? "default" : body.threadId());

        stream.subscribe(
                delta -> send(emitter, Map.of("type", "text-delta", "textDelta", delta)),
                error -> {
                    send(emitter, Map.of("type", "error", "error", "Stream interrupted"));
                    emitter.complete();
                },
                () -> {
                    send(emitter, Map.of("type", "done"));
                    emitter.complete();
                });
        return emitter;
    }

    private void send(SseEmitter emitter, Map<String, Object> event) {
        try {
            emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        } catch (Exception closed) {
            emitter.completeWithError(closed);
        }
    }
}
