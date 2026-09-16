package haven.loom.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import haven.loom.agent.AgentDefinition;
import haven.loom.agent.LoomAgent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The trace is a contract with the UI: ReviewTrace.tsx maps over
 * steps/remembered, so a missing key takes the case page down. The expected
 * shape is AgentTrace in frontend/src/api/client.ts.
 *
 * The fixture names the act tool through AgentDefinition.ACT_TOOL rather than a
 * literal: a contract test that keeps asserting a tool which no longer exists
 * stays green while describing an app that is gone.
 */
class ReviewTraceContractTest {

    private static LoomAgent.ReviewResult result(LoomAgent.ReviewResult.Usage usage) {
        return new LoomAgent.ReviewResult(
                "A case is waiting on a verdict.",
                List.of(new LoomAgent.ReviewResult.Step(
                        "Anvil's call is defensible and its reasoning is stated; agreeing.",
                        "STOP",
                        List.of(new LoomAgent.ReviewResult.ToolCall(
                                AgentDefinition.ACT_TOOL,
                                Map.of("action", "agree", "priority", "medium"))))),
                List.of(new LoomAgent.ReviewResult.Recalled("user", "an earlier turn")),
                usage,
                "Agreed.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void traceMatchesTheUiAgentTraceInterface() {
        Map<String, Object> trace =
                AgentActivitiesImpl.trace(result(new LoomAgent.ReviewResult.Usage(820, 30, 850)), 1234L);

        assertThat(trace.keySet()).containsExactlyInAnyOrder("prompt", "ms", "steps", "remembered", "usage");
        assertThat(trace.get("prompt")).isInstanceOf(String.class);
        assertThat(trace.get("ms")).isEqualTo(1234L);

        List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.getFirst().keySet())
                .containsExactlyInAnyOrder("text", "finishReason", "toolCalls", "toolResults");

        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) steps.getFirst().get("toolCalls");
        assertThat(toolCalls).singleElement().satisfies(call -> {
            assertThat(call.keySet()).containsExactlyInAnyOrder("name", "args");
            assertThat(call.get("name")).isEqualTo(AgentDefinition.ACT_TOOL);
            // The UI pretty-prints args with JSON.stringify(args, null, 2): a raw
            // JSON *string* here double-encodes into escaped garbage on the page.
            assertThat(call.get("args"))
                    .as("args must be a parsed object, never the JSON string Spring AI returns")
                    .isInstanceOf(Map.class);
        });

        List<Map<String, Object>> remembered = (List<Map<String, Object>>) trace.get("remembered");
        assertThat(remembered).singleElement().satisfies(recall ->
                assertThat(recall.keySet()).containsExactlyInAnyOrder("role", "text"));

        Map<String, Object> usage = (Map<String, Object>) trace.get("usage");
        assertThat(usage.keySet()).containsExactlyInAnyOrder("input", "output", "total");
        assertThat(usage.get("total")).isEqualTo(850);
    }

    @Test
    @SuppressWarnings("unchecked")
    void absentTokenCountsStayNullKeysRatherThanDisappearing() {
        Map<String, Object> trace =
                AgentActivitiesImpl.trace(result(new LoomAgent.ReviewResult.Usage(null, null, null)), 1L);

        Map<String, Object> usage = (Map<String, Object>) trace.get("usage");
        assertThat(usage.keySet())
                .as("the UI renders usage.input ?? '—'; the KEYS must exist even when the counts do not")
                .containsExactlyInAnyOrder("input", "output", "total");
        assertThat(usage.get("input")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolResultsAreEmptyNeverInvented() {
        Map<String, Object> trace =
                AgentActivitiesImpl.trace(result(new LoomAgent.ReviewResult.Usage(1, 2, 3)), 1L);

        List<Map<String, Object>> steps = (List<Map<String, Object>>) trace.get("steps");
        assertThat((List<Object>) steps.getFirst().get("toolResults"))
                .as("Spring AI executes tools internally; results are not observable per round. "
                        + "The trace teaches how the agent reasoned — fabricated results would teach a lie.")
                .isEmpty();
    }
}
