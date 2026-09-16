package haven.loom.agent;

import haven.loom.domain.Case;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/**
 * The agent. One definition, two surfaces: the daemon (:jobs) calls review()
 * each sweep to pass judgement on Anvil's decisions; the API (:api) streams chat().
 *
 * Runs in Spring beans and Temporal activities — never inside workflow code,
 * which is why the items service and OpenAI client work from here at all.
 */
@Component
public class LoomAgent {

    private final ChatClient client;
    private final ChatMemory memory;

    public LoomAgent(ChatModel model, ChatMemory memory, AgentDefinition definition, CaseTools tools) {
        this.memory = memory;
        this.client = ChatClient.builder(model)
                .defaultSystem(definition.instructions())
                .defaultTools(tools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }

    /**
     * One triage, shaped for the UI's DecisionTrace without leaking Spring AI
     * types. `steps` holds only the FINAL round — Spring AI executes tool calls
     * internally, so intermediate rounds are not observable. Never fabricate
     * steps: an invented reasoning trace teaches a lie.
     */
    public record ReviewResult(
            String prompt, List<Step> steps, List<Recalled> remembered, Usage usage, String text) {

        /** `args` is a parsed object — the UI JSON.stringify's it; a raw JSON string double-encodes. */
        public record ToolCall(String name, Object args) {}

        public record Step(String text, String finishReason, List<ToolCall> toolCalls) {}

        public record Recalled(String role, String text) {}

        public record Usage(Integer input, Integer output, Integer total) {}
    }

    /**
     * One sweep verdict.
     *
     * The memory thread is per CASE, so a verdict is never contaminated by
     * reasoning about an unrelated one — and so the progression that matters
     * (watched, disputed, still nothing, escalate) reads as one conversation.
     * Noticing patterns ACROSS cases is done by retrieval instead: the agent has
     * list-cases and can go and look. That is slower than letting memory bleed
     * between cases, and far easier to explain when a verdict is questioned.
     */
    public ReviewResult review(Case theCase) {
        String threadId = theCase.id().toString();
        // The mandate lives in the system prompt; this only presents the case and
        // reminds the agent that silence is one of its options. Restating the
        // policy here would give it two places to drift apart.
        String prompt = "Review this case. If Anvil's stated reason supports its action, agree. If it does not, "
                + "dispute and say what does not add up. If there is nothing to judge yet, leave it alone "
                + "unless its deadline is close, in which case escalate.\n\n"
                + CaseTools.describe(theCase);

        // Before the call — afterwards memory contains this turn too.
        List<ReviewResult.Recalled> remembered = memory.get(threadId).stream()
                .map(m -> new ReviewResult.Recalled(
                        m.getMessageType().getValue(), m.getText() == null ? "" : m.getText()))
                .toList();

        ChatResponse response = client.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, threadId))
                .call()
                .chatResponse();

        if (response == null) {
            return new ReviewResult(prompt, List.of(), remembered, new ReviewResult.Usage(null, null, null), "");
        }

        return new ReviewResult(
                prompt,
                steps(response),
                remembered,
                usage(response),
                response.getResult().getOutput().getText());
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Spring AI hands tool arguments back as a JSON string; the trace carries objects. */
    private static Object parseArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(raw, Object.class);
        } catch (RuntimeException notJson) {
            return raw;
        }
    }

    private static List<ReviewResult.Step> steps(ChatResponse response) {
        return response.getResults().stream()
                .map(generation -> {
                    AssistantMessage message = generation.getOutput();
                    List<ReviewResult.ToolCall> calls = message.hasToolCalls()
                            ? message.getToolCalls().stream()
                                    .map(c -> new ReviewResult.ToolCall(c.name(), parseArgs(c.arguments())))
                                    .toList()
                            : List.of();
                    return new ReviewResult.Step(
                            message.getText() == null ? "" : message.getText(),
                            generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason(),
                            calls);
                })
                .toList();
    }

    private static ReviewResult.Usage usage(ChatResponse response) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return usage == null
                ? new ReviewResult.Usage(null, null, null)
                : new ReviewResult.Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    /** Streaming chat — another lens onto the same agent, independent of the daemon. */
    public Flux<String> chat(String message, String threadId) {
        return client.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, threadId))
                .stream()
                .content();
    }
}
