package haven.loom.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import haven.loom.domain.AnvilDecision;
import haven.loom.domain.Case;
import haven.loom.domain.CaseState;
import haven.loom.domain.Channel;
import haven.loom.orchestration.CaseOrchestrator;
import haven.loom.persistence.CaseProperties;
import haven.loom.persistence.CaseService;
import haven.loom.persistence.PlanLimitReachedException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What one poll of Anvil must and must not do.
 *
 * Anvil and the database are both mocked: this asserts ingest's DECISIONS —
 * when it opens, when it only refreshes, and what it does when something
 * refuses — not what either dependency stores. The round trips against real
 * infrastructure are CaseServiceTest's job.
 */
class IngestActivitiesImplTest {

    private static final Duration SLA = Duration.ofHours(24);

    private AnvilClient anvil;
    private CaseService cases;
    private CaseOrchestrator orchestrator;
    private IngestActivitiesImpl ingest;

    @BeforeEach
    void setUp() {
        anvil = mock(AnvilClient.class);
        cases = mock(CaseService.class);
        orchestrator = mock(CaseOrchestrator.class);
        ingest = new IngestActivitiesImpl(anvil, cases, orchestrator, new CaseProperties(SLA));
    }

    private static AnvilClient.AnvilItem item(String id) {
        return new AnvilClient.AnvilItem(id, "Item " + id, "draft");
    }

    private static Case openedCase(String anvilItemId) {
        return new Case(
                UUID.randomUUID(),
                anvilItemId,
                "Item " + anvilItemId,
                CaseState.WATCHING,
                "draft",
                null,
                null,
                OffsetDateTime.now(ZoneOffset.UTC).plus(SLA),
                Map.of(),
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void anvilReturns(List<AnvilClient.AnvilItem> items, Map<String, AnvilDecision> decisions)
            throws Exception {
        when(anvil.read()).thenReturn(new AnvilClient.Snapshot(items, decisions));
    }

    @Test
    void opensACaseForAnItemItHasNotSeenAndStartsItsWorkflow() throws Exception {
        anvilReturns(List.of(item("anvil-1")), Map.of());
        when(cases.findByAnvilItemId("anvil-1")).thenReturn(Optional.empty());
        Case opened = openedCase("anvil-1");
        when(cases.openCase(any(), eq("anvil-1"), any(), any(), eq(SLA), any(), any(), any()))
                .thenReturn(opened);

        assertThat(ingest.ingestFromAnvil()).isEqualTo(1);

        verify(orchestrator).startProcessCase(opened.id(), SLA);
        verify(cases).syncFromAnvil(eq(opened.id()), any(), eq("draft"), any(), any(), eq(Channel.SYSTEM));
    }

    @Test
    void refreshesAnItemItAlreadyWatchesWithoutOpeningASecondCase() throws Exception {
        // Ingest runs on a timer and sees the same items over and over. Opening
        // again would duplicate the case AND meter a second unit.
        Case existing = openedCase("anvil-1");
        anvilReturns(List.of(item("anvil-1")), Map.of());
        when(cases.findByAnvilItemId("anvil-1")).thenReturn(Optional.of(existing));

        assertThat(ingest.ingestFromAnvil()).isEqualTo(1);

        verify(cases, never()).openCase(any(), any(), any(), any(), any(), any(), any(), any());
        verify(orchestrator, never()).startProcessCase(any(), any());
        verify(cases).syncFromAnvil(eq(existing.id()), any(), any(), any(), any(), any());
    }

    @Test
    void carriesAnvilsDecisionOntoTheCase() throws Exception {
        Case existing = openedCase("anvil-1");
        AnvilDecision decision =
                new AnvilDecision("approve", "low", "Routine.", OffsetDateTime.now(ZoneOffset.UTC));
        anvilReturns(List.of(item("anvil-1")), Map.of("anvil-1", decision));
        when(cases.findByAnvilItemId("anvil-1")).thenReturn(Optional.of(existing));

        ingest.ingestFromAnvil();

        verify(cases).syncFromAnvil(eq(existing.id()), any(), any(), eq(decision), any(), any());
    }

    @Test
    void keepsRefreshingKnownCasesAfterTheAllowanceRunsOut() throws Exception {
        // A billing limit on NEW cases must not stop loom supervising the ones
        // it already opened — those are live, and letting them go stale would
        // turn a plan limit into a supervision outage.
        Case known = openedCase("anvil-known");
        anvilReturns(List.of(item("anvil-new"), item("anvil-known"), item("anvil-new-2")), Map.of());
        when(cases.findByAnvilItemId("anvil-new")).thenReturn(Optional.empty());
        when(cases.findByAnvilItemId("anvil-new-2")).thenReturn(Optional.empty());
        when(cases.findByAnvilItemId("anvil-known")).thenReturn(Optional.of(known));
        when(cases.openCase(any(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new PlanLimitReachedException(10));

        assertThat(ingest.ingestFromAnvil()).isEqualTo(1);

        verify(cases).syncFromAnvil(eq(known.id()), any(), any(), any(), any(), any());
        // Refused once, then not attempted again for the rest of the poll.
        verify(cases, times(1)).openCase(any(), anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unwindsACaseWhoseWorkflowCouldNotStart() throws Exception {
        // A case with no workflow has no deadline and nothing will ever review
        // it — it would sit in `watching` forever having spent a metered unit.
        anvilReturns(List.of(item("anvil-1")), Map.of());
        when(cases.findByAnvilItemId("anvil-1")).thenReturn(Optional.empty());
        Case opened = openedCase("anvil-1");
        when(cases.openCase(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(opened);
        doThrow(new IllegalStateException("temporal is down"))
                .when(orchestrator)
                .startProcessCase(eq(opened.id()), any());

        assertThatThrownBy(() -> ingest.ingestFromAnvil()).isInstanceOf(IllegalStateException.class);

        verify(cases).discardCase(opened.id());
    }

    @Test
    void aBrokenAnvilFailsTheActivityRatherThanLookingEmpty() throws Exception {
        // Swallowing this would make an outage indistinguishable from "nothing
        // to supervise", which is exactly the silence loom exists to break.
        when(anvil.read()).thenThrow(new java.io.IOException("connection refused"));

        assertThatThrownBy(() -> ingest.ingestFromAnvil())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not read Anvil");

        verify(cases, never()).openCase(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
