package haven.loom.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import haven.loom.domain.AnvilDecision;
import haven.loom.domain.Case;
import haven.loom.domain.CaseState;
import haven.loom.domain.Priority;
import haven.loom.domain.Review;
import haven.loom.domain.ReviewAction;
import haven.loom.orchestration.CaseOrchestrator;
import haven.loom.persistence.CaseService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The parts of the agent that are true without a model.
 *
 * What it decides needs a live gateway and a judgement call about quality;
 * neither belongs in a unit test. What it is TOLD, and what it is shown, are
 * plain data — and both have already drifted once.
 */
class AgentDefinitionTest {

    private static final AgentDaemonProperties DAEMON = new AgentDaemonProperties(Duration.ofSeconds(60));

    private AgentDefinition definition() {
        CaseTools tools = new CaseTools(mock(CaseService.class), mock(CaseOrchestrator.class), DAEMON);
        return new AgentDefinition(DAEMON, tools);
    }

    private static Case theCase(AnvilDecision decision, Review review, Duration until) {
        return new Case(
                UUID.randomUUID(),
                "anvil-1",
                "Vendor NDA Template",
                CaseState.WATCHING,
                "flagged",
                decision,
                review,
                OffsetDateTime.now(ZoneOffset.UTC).plus(until),
                Map.of(),
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Test
    void theActTool_isLabelledAct_andEverythingElseRead() {
        // This drifted silently once: the label was derived by comparing against
        // a tool name that no longer existed after the app was reshaped, so the
        // one tool that CHANGES a case was advertised to the config surface as a
        // read.
        List<AgentDefinition.ToolSpec> specs = definition().toolSpecs();

        assertThat(specs).extracting(AgentDefinition.ToolSpec::id).contains(AgentDefinition.ACT_TOOL);
        assertThat(specs)
                .filteredOn(spec -> spec.kind().equals("act"))
                .extracting(AgentDefinition.ToolSpec::id)
                .containsExactly(AgentDefinition.ACT_TOOL);
    }

    @Test
    void theActToolNameMatchesARealTool() {
        // The constant and the @Tool annotation must agree. If a rename breaks
        // this, the test fails here rather than mislabelling the surface.
        assertThat(definition().toolSpecs())
                .extracting(AgentDefinition.ToolSpec::id)
                .contains(AgentDefinition.ACT_TOOL);
    }

    @Test
    void everyToolSpecCarriesItsArgumentNames() {
        // Names come from reflection, which needs -parameters on the build. If
        // that flag is ever dropped these become arg0/arg1 and tool calling
        // breaks in a way that looks like a model problem.
        assertThat(definition().toolSpecs())
                .filteredOn(spec -> spec.id().equals(AgentDefinition.ACT_TOOL))
                .singleElement()
                .satisfies(spec -> assertThat(spec.inputFields())
                        .containsExactly("caseId", "action", "priority", "rationale"));
    }

    @Test
    void theSweepSummarySpellsOutAnvilsReasonRatherThanItsStatus() {
        String summary = CaseTools.describe(theCase(
                new AnvilDecision("flag", "high", "Unusual indemnity clause.", OffsetDateTime.now(ZoneOffset.UTC)),
                null,
                Duration.ofHours(3)));

        assertThat(summary)
                .contains("Anvil decided: flag - Unusual indemnity clause.")
                .contains("Your previous verdict: (none)");
    }

    @Test
    void anUndecidedCaseSaysThereIsNothingToJudge() {
        // The mandate tells the agent not to invent a verdict on Anvil's behalf.
        // It can only follow that if the summary is unambiguous about the gap.
        String summary = CaseTools.describe(theCase(null, null, Duration.ofHours(3)));

        assertThat(summary).contains("there is no decision to judge");
    }

    @Test
    void theDeadlineIsGivenAsTimeLeftNotATimestamp() {
        // Date arithmetic is what a language model quietly gets wrong, and
        // "little time remains" is the condition it is told to escalate on.
        //
        // Asserted as a shape, not an exact figure: the two clock reads either
        // side of the call may or may not tick between them, so a three-hour
        // deadline renders as "3h 0m" or "2h 59m" depending on the machine.
        // Pinning the number would make this fail for a reason that has nothing
        // to do with what it is testing.
        assertThat(CaseTools.describe(theCase(null, null, Duration.ofHours(3))))
                .containsPattern("Deadline: in \\d+h \\d+m")
                // And no ISO timestamp on the deadline line, which is the thing
                // being avoided.
                .doesNotContainPattern("Deadline:.*\\d{4}-\\d{2}-\\d{2}");

        assertThat(CaseTools.describe(theCase(null, null, Duration.ofMinutes(-90))))
                .containsPattern("Deadline: PASSED \\d+h \\d+m ago");
    }

    @Test
    void aDeadlineWithinTheHourIsGivenInMinutes() {
        assertThat(CaseTools.describe(theCase(null, null, Duration.ofMinutes(20))))
                .containsPattern("Deadline: in \\d+m");
    }

    @Test
    void aDeadlineDaysOutIsGivenInDays() {
        assertThat(CaseTools.describe(theCase(null, null, Duration.ofDays(2))))
                .containsPattern("Deadline: in \\d+d \\d+h");
    }

    @Test
    void anEarlierVerdictIsShownSoTheAgentCanSeeNothingChanged() {
        // The escalate-after-a-stale-dispute rule depends on the agent knowing
        // it already disputed this case.
        String summary = CaseTools.describe(theCase(
                new AnvilDecision("approve", "low", "Routine.", OffsetDateTime.now(ZoneOffset.UTC)),
                new Review(
                        ReviewAction.DISPUTE,
                        Priority.HIGH,
                        "Approving an unreviewed indemnity clause is not routine.",
                        OffsetDateTime.now(ZoneOffset.UTC)),
                Duration.ofHours(2)));

        assertThat(summary).contains("Your previous verdict: dispute - Approving an unreviewed");
    }

    @Test
    void theMandateTellsTheAgentItMayDoNothing() {
        // Without this the agent has no licence to stay silent, and an undecided
        // case would get an invented verdict every sweep.
        assertThat(definition().instructions())
                .contains("Doing nothing is a legitimate outcome")
                .contains("do not dispute an absence");
    }
}
