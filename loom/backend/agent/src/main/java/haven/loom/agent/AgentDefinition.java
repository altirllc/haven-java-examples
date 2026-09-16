package haven.loom.agent;

import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * What the agent IS — its system prompt, memory shape, and the verbs it can
 * call — exposed so GET /api/agent/config can show developers the real thing.
 *
 * The prompt below is the entire supervision policy. There is no rule engine and
 * no scoring model: if loom judges a decision badly, this text is where it is
 * wrong.
 *
 * A teaching surface, so it must not drift: the tool catalogue is read off the
 * live @Tool annotations by reflection rather than restated, which is why
 * adding a tool needs no edit here.
 */
@Component
@org.springframework.boot.context.properties.EnableConfigurationProperties(AgentDaemonProperties.class)
public class AgentDefinition {

    /**
     * The one tool that changes anything. Named here rather than inferred so the
     * config surface cannot quietly relabel an act as a read when a tool is
     * renamed — which is exactly what happened when this app was reshaped.
     */
    public static final String ACT_TOOL = "review-case";

    /** Recent history plus semantic (kNN) recall over past turns. */
    public static final int LAST_MESSAGES = 20;
    public static final int SEMANTIC_RECALL_TOP_K = 5;
    public static final int SEMANTIC_RECALL_MESSAGE_RANGE = 2;

    private final AgentDaemonProperties daemon;
    private final CaseTools tools;

    public AgentDefinition(AgentDaemonProperties daemon, CaseTools tools) {
        this.daemon = daemon;
        this.tools = tools;
    }

    public record ToolSpec(String id, String kind, String description, List<String> inputFields) {}

    /**
     * The entire decision-making policy, in plain English. There is no rule
     * engine — this prompt is it.
     */
    public String instructions() {
        return String.join(
                " ",
                "You are an autonomous supervisor. Another agent — Anvil's triager — decides what happens to work"
                        + " items. You do not decide those items. You judge whether Anvil's decision was defensible,"
                        + " and you are the only thing standing between a bad automated call and nobody noticing."
                        + " On a schedule — every " + daemon.display() + " — you sweep the open cases closest to"
                        + " their review deadline; no human prompts you. You are also reachable here in chat.",
                "For each case you see what Anvil decided and the reason it gave. Ask one question: does that"
                        + " reason actually support that action for this item? If it does, call review-case with"
                        + " `agree`. If the stated reason does not support the action, contradicts the item, or"
                        + " ignores something plainly risky, call review-case with `dispute` and say exactly what"
                        + " does not add up. Dispute is expensive — it tells a human that an automated decision"
                        + " cannot be trusted — so do not spend it on wording you would have chosen differently.",
                "When Anvil has NOT decided yet there is nothing to judge. Do not invent a verdict on its behalf,"
                        + " and do not dispute an absence. Leave the case alone and let its deadline run — unless"
                        + " little time remains, in which case call review-case with `escalate` so a person sees it"
                        + " before it lapses. Escalate is also the right call when you disputed a case earlier and"
                        + " nothing has changed since.",
                "Doing nothing is a legitimate outcome of a sweep. You are not required to call review-case for"
                        + " every case you are shown, and a case you leave alone will come back to you next sweep,"
                        + " closer to its deadline.",
                "Your rationale is the product. A human reads it to decide whether to act, so one specific"
                        + " sentence about THIS case beats a general statement that would fit any case.",
                "In chat you can act, not just answer: revisit a case by calling review-case again — a verdict is"
                        + " never final. Use list-cases (filter by state) and get-case for a specific id, and"
                        + " agent-status for questions about your schedule, when you last reviewed anything, or how"
                        + " many cases are past their deadline. You cannot decide Anvil's items, open cases, or"
                        + " delete anything; if asked, say so plainly. Speak as the supervisor that does this work,"
                        + " and be concise, with standard capitalization and punctuation.");
    }

    public Map<String, Object> memory() {
        return Map.of(
                "lastMessages",
                LAST_MESSAGES,
                "semanticRecall",
                Map.of("topK", SEMANTIC_RECALL_TOP_K, "messageRange", SEMANTIC_RECALL_MESSAGE_RANGE));
    }

    /** Read off the live tools, so this can never disagree with what runs. */
    public List<ToolSpec> toolSpecs() {
        return java.util.Arrays.stream(tools.getClass().getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(m -> {
                    Tool tool = m.getAnnotation(Tool.class);
                    // Names come from reflection (the build passes -parameters),
                    // which is the same source Spring AI uses to build the tool's
                    // JSON schema — so this cannot disagree with the real call.
                    List<String> fields = java.util.Arrays.stream(m.getParameters())
                            .map(java.lang.reflect.Parameter::getName)
                            .toList();
                    return new ToolSpec(
                            tool.name(), ACT_TOOL.equals(tool.name()) ? "act" : "read", tool.description(), fields);
                })
                .sorted(java.util.Comparator.comparing(ToolSpec::id))
                .toList();
    }

    public int batchSize() {
        return AgentDaemonProperties.BATCH_SIZE;
    }

    public String interval() {
        return daemon.display();
    }
}
