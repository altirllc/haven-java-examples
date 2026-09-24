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
 * The model list proves the virtual key is valid, shows what this tenant is
 * entitled to, and is the only part that measures the gateway itself — it stays
 * inside the cell and costs nothing.
 *
 * A chat completion proves the chat alias resolves to something live behind the
 * gateway, and an embedding proves the same for the embedding alias AND returns
 * the width, which has to match the pgvector column an app already created or
 * every write to its memory store fails later. Both leave the cell for a metered
 * vendor, so they are gated behind {@code deep} and default off: the sweep runs
 * every 60s, and re-buying that proof 1,440 times a day is not what it is worth.
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
        // The claim has to track the default: with deep off neither alias is
        // called and the width is never read, and a row that overstates what it
        // checked is worse than one that checks less.
        return properties.deep()
                ? "the tenant key is accepted and both model aliases answer at the expected width"
                : "the tenant key is accepted and the gateway serves both model aliases";
    }

    @Override
    public Outcome run() throws Exception {
        if (!properties.configured()) {
            return Outcome.skipped(
                    "MODELS_GATEWAY_ENDPOINT / MODELS_GATEWAY_API_KEY not set — from tenant-models-secret");
        }

        String base = properties.endpoint().replaceAll("/+$", "");
        String auth = "Bearer " + properties.apiKey();

        long listStarted = System.nanoTime();
        Http.Reply models = http.get(base + "/v1/models", "Authorization", auth);
        long listMillis = millisSince(listStarted);
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

        // Everything above is east-west and free: the key was accepted and the
        // gateway serves both aliases. What follows leaves the cell for a
        // metered vendor, so it is opt-in — see ModelsGatewayProperties#deep.
        if (!properties.deep()) {
            return Outcome.ok(
                    "both aliases are served",
                    "endpoint: " + base,
                    "gateway round trip: " + listMillis + "ms",
                    "chat alias: " + properties.chatModel() + " (not exercised)",
                    "embedding alias: " + properties.embeddingModel() + " (not exercised)",
                    "aliases offered: " + aliases.size(),
                    "set DEEP_LITELLM_PROBE=true to spend an inference proving they answer");
        }

        long chatStarted = System.nanoTime();
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
        long chatMillis = millisSince(chatStarted);
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

        long embeddingStarted = System.nanoTime();
        Http.Reply embedding = http.postJson(
                base + "/v1/embeddings",
                json.writeValueAsString(
                        java.util.Map.of("model", properties.embeddingModel(), "input", "plumb round trip")),
                "Authorization",
                auth);
        long embeddingMillis = millisSince(embeddingStarted);
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

        // Split by call: the gateway round trip is east-west and milliseconds,
        // while chat and embedding are vendor inference over north-south egress.
        // One combined number reads as "the gateway is slow" when it never is.
        return Outcome.ok(
                "both aliases answered",
                "endpoint: " + base,
                "gateway round trip: " + listMillis + "ms",
                "chat (" + properties.chatModel() + "): " + (reply.isBlank() ? "(empty reply)" : reply) + " — "
                        + chatMillis + "ms of vendor inference",
                "embedding (" + properties.embeddingModel() + "): " + width + " dimensions — " + embeddingMillis
                        + "ms of vendor inference",
                "aliases offered: " + aliases.size());
    }

    private static long millisSince(long startedAt) {
        return java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
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
