package haven.loom.api;

import haven.loom.domain.AuditLogEntry;
import haven.loom.domain.Case;
import haven.loom.domain.CaseState;
import haven.loom.domain.Channel;
import haven.loom.domain.Priority;
import haven.loom.domain.Review;
import haven.loom.domain.ReviewAction;
import haven.loom.domain.Role;
import haven.loom.orchestration.CaseOrchestrator;
import haven.loom.persistence.CaseClosedException;
import haven.loom.persistence.CaseProperties;
import haven.loom.persistence.CaseService;
import haven.loom.persistence.PlanLimitReachedException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Thin HTTP adapter. Owns transport only: parsing, the review signal, and the
 * status codes.
 *
 * Persistence and the audit log go through the cases service; orchestration goes
 * through the orchestrator. Opening a case starts its durable workflow, which is
 * what holds the SLA. agree/dispute/escalate are delivered to that workflow as a
 * signal — the exact same path and payload the agent's review-case tool uses —
 * so this controller never writes state.
 */
@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private static final Logger log = LoggerFactory.getLogger(CaseController.class);

    private final CaseService cases;
    private final CaseOrchestrator orchestrator;
    private final CurrentUser currentUser;
    private final CaseProperties properties;

    public CaseController(
            CaseService cases,
            CaseOrchestrator orchestrator,
            CurrentUser currentUser,
            CaseProperties properties) {
        this.cases = cases;
        this.orchestrator = orchestrator;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    /** What ingest posts when it finds an Anvil item loom is not yet watching. */
    public record OpenBody(String anvilItemId, String title, String anvilStatus, Map<String, Object> metadata) {}

    public record ReviewBody(String priority, String rationale) {}

    public record PatchBody(Map<String, Object> metadata) {}

    /**
     * Open a case for an Anvil item.
     *
     * Idempotent on `anvilItemId`: ingest re-reads the same items every tick, so
     * a second call returns the existing case with 200 rather than opening a
     * duplicate. Only a genuinely new case gets 201 — and only a new case starts
     * a workflow, because starting a second one for the same case would mean two
     * SLA clocks racing each other.
     */
    @PostMapping
    public ResponseEntity<Case> open(HttpServletRequest request, @RequestBody OpenBody body) {
        currentUser.require(request, Role.MEMBER);
        if (body == null || body.anvilItemId() == null || body.anvilItemId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "anvilItemId is required");
        }

        Optional<Case> existing = cases.findByAnvilItemId(body.anvilItemId());
        if (existing.isPresent()) {
            return ResponseEntity.ok(existing.get());
        }

        UUID id = UUID.randomUUID();
        Duration sla = properties.sla();
        Case opened = cases.openCase(
                id,
                body.anvilItemId(),
                body.title() == null ? body.anvilItemId() : body.title(),
                body.anvilStatus(),
                sla,
                body.metadata(),
                currentUser.of(request).email(),
                Channel.API);

        try {
            orchestrator.startProcessCase(opened.id(), sla);
        } catch (RuntimeException temporalIsDown) {
            // Unwind: a case with no workflow has no deadline and nothing will
            // ever review it, and the metered unit would be spent on a record
            // nobody can act on.
            log.warn("Could not start the workflow for case {} — unwinding", opened.id(), temporalIsDown);
            try {
                cases.discardCase(opened.id());
            } catch (RuntimeException unwindFailed) {
                log.error("Unwind failed for case {} — a metered unit may have leaked", opened.id(), unwindFailed);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not start the case workflow");
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(opened);
    }

    @GetMapping
    public List<Case> list(
            HttpServletRequest request,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer limit) {
        currentUser.require(request, Role.VIEWER);
        CaseState filter = state == null || state.isBlank() ? null : CaseState.fromWire(state);
        return cases.listCases(filter, limit, CaseService.Order.UPDATED);
    }

    @GetMapping("/{id}")
    public Case get(HttpServletRequest request, @PathVariable UUID id) {
        currentUser.require(request, Role.VIEWER);
        return cases.getCase(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
    }

    @GetMapping("/{id}/logs")
    public List<AuditLogEntry> logs(HttpServletRequest request, @PathVariable UUID id) {
        currentUser.require(request, Role.VIEWER);
        if (cases.getCase(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found");
        }
        return cases.getCaseLogs(id);
    }

    @PatchMapping("/{id}")
    public Case patch(HttpServletRequest request, @PathVariable UUID id, @RequestBody PatchBody body) {
        currentUser.require(request, Role.MEMBER);
        return cases.updateCase(
                        id,
                        body == null ? null : body.metadata(),
                        currentUser.of(request).email(),
                        Channel.API)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
    }

    @PostMapping("/{id}/agree")
    public ResponseEntity<Void> agree(
            HttpServletRequest request, @PathVariable UUID id, @RequestBody(required = false) ReviewBody body) {
        return review(request, id, ReviewAction.AGREE, body);
    }

    @PostMapping("/{id}/dispute")
    public ResponseEntity<Void> dispute(
            HttpServletRequest request, @PathVariable UUID id, @RequestBody(required = false) ReviewBody body) {
        return review(request, id, ReviewAction.DISPUTE, body);
    }

    @PostMapping("/{id}/escalate")
    public ResponseEntity<Void> escalate(
            HttpServletRequest request, @PathVariable UUID id, @RequestBody(required = false) ReviewBody body) {
        return review(request, id, ReviewAction.ESCALATE, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable UUID id) {
        currentUser.require(request, Role.ADMIN);
        return cases.deleteCase(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * One path for every human verdict, and the same one the agent takes: record
     * it, then signal the workflow. A 409 means the case already closed — the
     * workflow is gone, so the verdict has nowhere to land.
     */
    private ResponseEntity<Void> review(
            HttpServletRequest request, UUID id, ReviewAction action, ReviewBody body) {
        currentUser.require(request, Role.MEMBER);
        if (cases.getCase(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found");
        }
        Priority priority = body == null || body.priority() == null || body.priority().isBlank()
                ? Priority.MEDIUM
                : Priority.fromWire(body.priority());
        String rationale = body == null || body.rationale() == null ? "" : body.rationale();
        String actor = currentUser.of(request).email();

        cases.recordReview(
                id,
                new Review(action, priority, rationale, OffsetDateTime.now()),
                actor,
                Channel.API);
        boolean delivered = orchestrator.signalReview(id, action, priority, rationale, actor, Channel.API);
        return delivered
                ? ResponseEntity.accepted().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(CaseClosedException.class)
    public ResponseEntity<Map<String, Object>> closed(CaseClosedException closed) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", closed.getMessage(), "code", "CASE_CLOSED"));
    }

    @ExceptionHandler(PlanLimitReachedException.class)
    public ResponseEntity<Map<String, Object>> planLimit(PlanLimitReachedException limit) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(Map.of("error", limit.getMessage(), "code", "PLAN_LIMIT_REACHED"));
    }
}
