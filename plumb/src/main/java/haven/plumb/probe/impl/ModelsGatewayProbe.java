package haven.plumb.probe.impl;

import haven.plumb.config.ModelsGatewayProperties;
import haven.plumb.probe.Probe;
import haven.plumb.probe.ProbeGroup;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The model gateway: the key is accepted, the aliases exist, and both of them
 * actually answer.
 *
 * Three calls because they fail independently and an app needs all three. The
 * model list proves the virtual key is valid and shows what this tenant is
 * entitled to. A chat completion proves the chat alias resolves to something
 * live behind the gateway. An embedding proves the same for the embedding alias
 * AND returns the width — which has to match the pgvector column an app already
 * created, or every write to its memory store fails later.
 *
 * Apps hold no vendor credentials and call aliases, never vendor model names, so
 * these are exactly the failures that survive a green deployment: the tenant key
 * was never minted, or the alias was renamed on the gateway, and the app finds
 * out at its first inference.
 */
@Component
@Order(10)
public class ModelsGatewayProbe implements Probe {

    private final ModelsGatewayProperties properties;
    private final Http http;
    private final ObjectMapper json;

    public ModelsGatewayProbe(ModelsGatewayProperties properties, Http http, ObjectMapper json) {
        this.properties = properties;
        this.http = http;
        this.json = json;
    }

    @Override
    public String id() {
        return "models.gateway";
    }

    @Override
    public ProbeGroup group() {
        return ProbeGroup.PLATFORM;
    }

    @Override
    public String title() {
        return "Model gateway";
    }

    @Override
    public String proves() {
        return "the tenant key is accepted and both model aliases answer at the expected width";
    }

    @Override
    public Outcome run() throws Exception {
        if (!properties.configured()) {
            return Outcome.skipped(
                    "MODELS_GATEWAY_ENDPOINT / MODELS_GATEWAY_API_KEY not set — from tenant-models-secret");
        }

        String base = properties.endpoint().replaceAll("/+$", "");
        String auth = "Bearer " + properties.apiKey();

        Http.Reply models = http.get(base + "/v1/models", "Authorization", auth);
        if (!models.ok()) {
            // 401 here is the single most common model-gateway failure and it
            // means the key, not the network — say so rather than "HTTP 401".
            String hint = models.status() == 401
                    ? "the virtual key was rejected — check tenant-models-secret"
                    : models.snippet();
            return Outcome.fail("GET /v1/models returned " + models.status(), hint);
        }

        List<String> aliases = aliases(models.body());
        if (!aliases.contains(properties.chatModel())) {
            return Outcome.fail(
                    "the gateway does not serve the chat alias " + properties.chatModel(),
                    "aliases offered: " + String.join(", ", aliases));
        }
        if (!aliases.contains(properties.embeddingModel())) {
            return Outcome.fail(
                    "the gateway does not serve the embedding alias " + properties.embeddingModel(),
                    "aliases offered: " + String.join(", ", aliases));
        }

        Http.Reply chat = http.postJson(
                base + "/v1/chat/completions",
                json.writeValueAsString(java.util.Map.of(
                        "model",
                        properties.chatModel(),
                        "messages",
                        List.of(java.util.Map.of("role", "user", "content", "Reply with the single word: pong")),
                        // max_completion_tokens, not max_tokens: in a cell the chat
                        // alias is a GPT-5.x reasoning model, which answers 400 to
                        // max_tokens. The cap also covers the hidden reasoning, so
                        // a tight one comes back as an empty reply — 1024 leaves
                        // room for both (haven-recall learned the same).
                        "max_completion_tokens",
                        1024)),
                "Authorization",
                auth);
        if (!chat.ok()) {
            return Outcome.fail("chat completion returned " + chat.status(), chat.snippet());
        }
        String reply = json.readTree(chat.body())
                .path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asString("")
                .strip();

        Http.Reply embedding = http.postJson(
                base + "/v1/embeddings",
                json.writeValueAsString(
                        java.util.Map.of("model", properties.embeddingModel(), "input", "plumb round trip")),
                "Authorization",
                auth);
        if (!embedding.ok()) {
            return Outcome.fail("embedding returned " + embedding.status(), embedding.snippet());
        }
        int width = json.readTree(embedding.body())
                .path("data")
                .path(0)
                .path("embedding")
                .size();
        if (width != properties.embeddingDimensions()) {
            return Outcome.fail(
                    "embedding width is " + width + ", the platform standard is " + properties.embeddingDimensions(),
                    "a pgvector column built for " + properties.embeddingDimensions() + " will reject these");
        }

        return Outcome.ok(
                "both aliases answered",
                "endpoint: " + base,
                "chat (" + properties.chatModel() + "): " + (reply.isBlank() ? "(empty reply)" : reply),
                "embedding (" + properties.embeddingModel() + "): " + width + " dimensions",
                "aliases offered: " + aliases.size());
    }

    private List<String> aliases(String body) {
        List<String> ids = new ArrayList<>();
        for (JsonNode model : json.readTree(body).path("data")) {
            String id = model.path("id").asString("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }
}
