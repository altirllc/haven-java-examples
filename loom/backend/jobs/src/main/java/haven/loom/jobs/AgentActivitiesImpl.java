package haven.loom.jobs;

import haven.loom.agent.AgentDefinition;
import haven.loom.agent.LoomAgent;
import haven.loom.contracts.AgentActivities;
import haven.loom.domain.Case;
import haven.loom.persistence.CaseService;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The autonomous agent at work — one sweep of the open cases.
 *
 * This is an activity, not workflow code, which is the whole reason the agent
 * can exist: it reads config, calls the model gateway, and touches Postgres, none of which
 * survive replay. Nothing prompts it; it decides for itself.
 *
 * After each decision we persist a TRACE onto the `decided` row the agent just
 * wrote, turning every action into a worked example of how it reasoned. That is
 * a teaching aid — it is best-effort and never fails the sweep.
 */
@Component
public class AgentActivitiesImpl implements AgentActivities {

    private static final Logger log = LoggerFactory.getLogger(AgentActivitiesImpl.class);

    private final CaseService cases;
    private final LoomAgent agent;
    private final AgentDefinition definition;

    public AgentActivitiesImpl(CaseService cases, LoomAgent agent, AgentDefinition definition) {
        this.cases = cases;
        this.agent = agent;
        this.definition = definition;
    }

    @Override
    public int reviewOpenCases() {
        // Nearest deadline first, not oldest first: a bounded sweep should spend
        // its budget on the cases closest to lapsing.
        List<Case> open = cases.openCasesBySlaDueAt(definition.batchSize());

        for (Case theCase : open) {
            long startedAt = System.currentTimeMillis();
            LoomAgent.ReviewResult result = agent.review(theCase);

            try {
                cases.attachReviewTrace(theCase.id(), trace(result, System.currentTimeMillis() - startedAt));
            } catch (RuntimeException traceFailed) {
                log.warn("Trace persistence failed for case {} — sweep continues", theCase.id(), traceFailed);
            }
        }

        return open.size();
    }

    /**
     * The UI's AgentTrace contract (frontend/src/api/client.ts). Not
     * optional: DecisionTrace maps over steps/remembered, so a missing key
     * throws. HashMap because token counts are legitimately null.
     */
    static Map<String, Object> trace(LoomAgent.ReviewResult result, long ms) {
        Map<String, Object> usage = new HashMap<>();
        usage.put("input", result.usage().input());
        usage.put("output", result.usage().output());
        usage.put("total", result.usage().total());

        List<Map<String, Object>> steps = result.steps().stream()
                .map(step -> {
                    Map<String, Object> s = new HashMap<>();
                    s.put("text", step.text());
                    s.put("finishReason", step.finishReason());
                    s.put(
                            "toolCalls",
                            step.toolCalls().stream()
                                    .map(call -> Map.of("name", call.name(), "args", call.args()))
                                    .toList());
                    // Not observable per round (Spring AI runs tools internally); never invented.
                    s.put("toolResults", List.of());
                    return s;
                })
                .toList();

        List<Map<String, Object>> remembered = result.remembered().stream()
                .map(r -> Map.<String, Object>of("role", r.role(), "text", r.text()))
                .toList();

        Map<String, Object> trace = new HashMap<>();
        trace.put("prompt", result.prompt());
        trace.put("ms", ms);
        trace.put("steps", steps);
        trace.put("remembered", remembered);
        trace.put("usage", usage);
        return trace;
    }
}
