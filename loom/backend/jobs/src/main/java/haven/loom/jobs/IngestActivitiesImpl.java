package haven.loom.jobs;

import haven.loom.contracts.IngestActivities;
import haven.loom.domain.AnvilDecision;
import haven.loom.domain.Case;
import haven.loom.domain.Channel;
import haven.loom.orchestration.CaseOrchestrator;
import haven.loom.persistence.CaseProperties;
import haven.loom.persistence.CaseService;
import haven.loom.persistence.PlanLimitReachedException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One poll of Anvil: open a case for anything new, and refresh what loom
 * already watches.
 *
 * This is where loom's supervision actually begins. Everything downstream — the
 * SLA, the agent's verdict, the escalation — hangs off a case existing, and a
 * case exists because this found an item.
 */
@Component
public class IngestActivitiesImpl implements IngestActivities {

    private static final Logger log = LoggerFactory.getLogger(IngestActivitiesImpl.class);

    private final AnvilClient anvil;
    private final CaseService cases;
    private final CaseOrchestrator orchestrator;
    private final CaseProperties caseProperties;

    public IngestActivitiesImpl(
            AnvilClient anvil, CaseService cases, CaseOrchestrator orchestrator, CaseProperties caseProperties) {
        this.anvil = anvil;
        this.cases = cases;
        this.orchestrator = orchestrator;
        this.caseProperties = caseProperties;
    }

    @Override
    public int ingestFromAnvil() {
        AnvilClient.Snapshot snapshot;
        try {
            snapshot = anvil.read();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ingest interrupted", interrupted);
        } catch (Exception unreachable) {
            // Let it out: the activity retries, and the daemon survives even
            // when the retries are exhausted. Swallowing it here would make a
            // broken Anvil look like an empty one, which is the same shape as
            // "nothing to supervise" and would hide the outage completely.
            throw new IllegalStateException("Could not read Anvil: " + unreachable.getMessage(), unreachable);
        }

        int touched = 0;
        boolean allowanceSpent = false;

        for (AnvilClient.AnvilItem item : snapshot.items()) {
            Optional<Case> existing = cases.findByAnvilItemId(item.id());
            UUID caseId;

            if (existing.isPresent()) {
                caseId = existing.get().id();
            } else if (allowanceSpent) {
                // Keep going: refreshing the cases loom already watches still
                // works, and stopping the whole poll would let live cases go
                // stale because of a billing limit on new ones.
                continue;
            } else {
                Optional<UUID> opened = open(item);
                if (opened.isEmpty()) {
                    allowanceSpent = true;
                    continue;
                }
                caseId = opened.get();
            }

            AnvilDecision decision = snapshot.decisions().get(item.id());
            cases.syncFromAnvil(caseId, item.title(), item.status(), decision, "system", Channel.SYSTEM);
            touched++;
        }

        log.info("Ingest touched {} case(s) from {} Anvil item(s)", touched, snapshot.items().size());
        return touched;
    }

    /**
     * Open a case and start the workflow that holds its deadline. Empty when the
     * plan's allowance is spent.
     *
     * The unwind matters: a case whose workflow never started has no deadline
     * and nothing will ever review it, so it would sit in `watching` forever
     * while having consumed a metered unit. Same reasoning as the REST route.
     */
    private Optional<UUID> open(AnvilClient.AnvilItem item) {
        Duration sla = caseProperties.sla();
        Case opened;
        try {
            opened = cases.openCase(
                    UUID.randomUUID(),
                    item.id(),
                    item.title() == null || item.title().isBlank() ? item.id() : item.title(),
                    item.status(),
                    sla,
                    Map.of("source", "anvil"),
                    "system",
                    Channel.SYSTEM);
        } catch (PlanLimitReachedException spent) {
            log.warn("Plan allowance reached — not opening new cases this poll: {}", spent.getMessage());
            return Optional.empty();
        }

        try {
            orchestrator.startProcessCase(opened.id(), sla);
        } catch (RuntimeException temporalIsDown) {
            log.warn("Could not start the workflow for case {} — unwinding", opened.id(), temporalIsDown);
            try {
                cases.discardCase(opened.id());
            } catch (RuntimeException unwindFailed) {
                log.error("Unwind failed for case {} — a metered unit may have leaked", opened.id(), unwindFailed);
            }
            throw temporalIsDown;
        }
        log.info("Opened case {} for Anvil item {}", opened.id(), item.id());
        return Optional.of(opened.id());
    }
}
