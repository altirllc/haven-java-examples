package haven.loom.jobs;

import haven.loom.domain.AnvilDecision;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads Anvil. The only class in loom that knows Anvil exists.
 *
 * It lives in :jobs rather than :api because only the worker talks to Anvil —
 * ingest polls it, and acting back on a dispute will too. The REST surface
 * serves cases out of loom's own database and never reaches upstream. Keeping
 * it here also keeps HTTP off the workflow classpath without needing the fence
 * to say so.
 *
 * The JDK client, not RestClient: this is an integration seam, and the fewer
 * layers between the call and the socket, the more legible the failure.
 *
 * Two calls per poll, not one per item. Anvil exposes its triager's decisions
 * as a single activity feed, so the rationale — the thing loom actually reasons
 * about — costs one request rather than one per item. A per-item log fetch
 * would turn every poll into an N+1 against a neighbour.
 *
 * REST for the write-back as well as the reads, though Anvil also exposes an
 * MCP surface and MCP is the only one of the two that can carry a credential.
 * The reasoning, because it contradicts the original plan: the blocker is
 * AUTHENTICATION, not protocol. Neither path works in a cell without a Zitadel
 * service account, and the reads can only be done over REST (MCP has no tool
 * that returns the triager's rationale). Adding a second protocol with a second
 * auth story would double the surface while both halves stay blocked, and the
 * MCP client could not be tested against anything real. When a credential
 * exists, moving the WRITES to MCP is a contained change — it is one method on
 * this class.
 */
@Component
public class AnvilClient {

    private final AnvilProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    public AnvilClient(AnvilProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                // Not followed on purpose: every endpoint here answers directly,
                // and a redirect means something is in front of Anvil that
                // should not be (an SSO portal, a captive proxy). Following it
                // would turn that into a successful-looking empty result.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** One item as Anvil reports it. Only the fields loom supervises. */
    public record AnvilItem(String id, String title, String status) {}

    /**
     * Anvil's items, plus its triager's decision for each where there is one.
     *
     * Items with no decision are still returned with a null decision — that is
     * the case loom cares about most, because it is the one whose deadline is
     * running with nothing to show for it.
     */
    public record Snapshot(List<AnvilItem> items, Map<String, AnvilDecision> decisions) {}

    public Snapshot read() throws IOException, InterruptedException {
        List<AnvilItem> items = items();
        return new Snapshot(items, decisions());
    }

    private List<AnvilItem> items() throws IOException, InterruptedException {
        JsonNode body = get("/api/items");
        List<AnvilItem> items = new ArrayList<>();
        for (JsonNode node : body) {
            String id = node.path("id").asString("");
            if (id.isBlank()) {
                continue;
            }
            items.add(new AnvilItem(id, node.path("title").asString(""), node.path("status").asString("")));
            if (items.size() >= properties.pageSize()) {
                break;
            }
        }
        return items;
    }

    /**
     * The triager's recent decisions, keyed by item id.
     *
     * The feed is newest-first and bounded, so an item decided long ago may not
     * appear. That is why ingest only ever ADDS a decision it finds and never
     * clears one it already recorded: absence here means "not in the recent
     * window", not "undecided".
     */
    private Map<String, AnvilDecision> decisions() throws IOException, InterruptedException {
        JsonNode body = get("/api/agent/activity");
        Map<String, AnvilDecision> decisions = new LinkedHashMap<>();
        for (JsonNode action : body.path("actions")) {
            String itemId = action.path("itemId").asString("");
            if (itemId.isBlank() || decisions.containsKey(itemId)) {
                // First wins: the feed is newest-first, so an older decision for
                // the same item must not overwrite the current one.
                continue;
            }
            decisions.put(
                    itemId,
                    new AnvilDecision(
                            action.path("action").asString(null),
                            action.path("priority").asString(null),
                            action.path("rationale").asString(null),
                            parseTime(action.path("at").asString(null))));
        }
        return decisions;
    }

    /** What came of asking Anvil to look again. */
    public record PushBack(boolean delivered, String detail) {}

    /**
     * Ask Anvil to flag an item, so a human sees it again.
     *
     * `flag` rather than reject: loom supervises, it does not overrule. Anvil's
     * flag is an escalation that keeps the item awaiting a decision, which is
     * exactly what a dispute means — loom is saying "a person should look at
     * this", not "the answer is no".
     *
     * A 409 is not a failure. It means Anvil already resolved the item while
     * loom was deliberating, so there is nothing left to flag; retrying would
     * never succeed and the case timeline should say so rather than show an
     * error.
     */
    public PushBack flagItem(String anvilItemId, String comment) throws IOException, InterruptedException {
        String body = json.writeValueAsString(Map.of("comment", comment == null ? "" : comment));
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create(base() + "/api/items/" + anvilItemId + "/flag"))
                .timeout(properties.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (properties.authenticated()) {
            request.header("Authorization", "Bearer " + properties.token());
        }

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409) {
            return new PushBack(false, "Anvil had already resolved the item");
        }
        refuseLoudly(response.statusCode(), "/api/items/" + anvilItemId + "/flag");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Anvil answered " + response.statusCode() + " when flagging " + anvilItemId);
        }
        return new PushBack(true, "Anvil flagged the item for a human");
    }

    private String base() {
        return properties.apiUrl().replaceAll("/+$", "");
    }

    /**
     * 401/403 is the EXPECTED failure in a cell, and "HTTP 403" on its own sends
     * people looking in the wrong place.
     */
    private void refuseLoudly(int status, String path) throws IOException {
        if (status == 401 || status == 403) {
            throw new IOException("Anvil refused loom at " + path + " (HTTP " + status
                    + "). Anvil resolves callers from edge headers, not from a bearer token — see"
                    + " loom/L0-FINDINGS.md.");
        }
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(properties.timeout())
                .GET();
        if (properties.authenticated()) {
            request.header("Authorization", "Bearer " + properties.token());
        }

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        refuseLoudly(response.statusCode(), path);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Anvil answered " + response.statusCode() + " for " + path);
        }
        return json.readTree(response.body());
    }

    /** A timestamp loom failed to parse is not worth failing a poll over. */
    private static OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException unparseable) {
            return null;
        }
    }
}
