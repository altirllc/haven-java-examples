package haven.loom.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The Anvil seam, against a real socket.
 *
 * A stub HTTP server rather than a mocked client: the thing worth testing here
 * is the wire — the paths called, the header sent, how a refusal is reported,
 * and whether loom reads Anvil's actual JSON correctly. Mocking the HTTP client
 * would test nothing but Jackson.
 *
 * The payloads below are the shapes Anvil really serves (haven-anvil
 * api/src/routes/items.ts and agent.ts), trimmed to the fields loom reads.
 */
class AnvilClientTest {

    private static final String ITEMS_JSON =
            """
            [
              {"id":"8f1c2e5a-0000-4000-8000-000000000001","title":"Vendor NDA Template",
               "description":"NDA for third-party vendors","status":"flagged",
               "metadata":{"priority":"high"},"attachments":[],
               "created_at":"2026-09-15T08:00:00.000Z","updated_at":"2026-09-15T08:05:00.000Z"},
              {"id":"8f1c2e5a-0000-4000-8000-000000000002","title":"Q1 Budget Proposal",
               "description":"Annual allocation","status":"draft",
               "metadata":{},"attachments":[],
               "created_at":"2026-09-15T08:01:00.000Z","updated_at":"2026-09-15T08:01:00.000Z"}
            ]
            """;

    // Newest first, exactly as Anvil orders it — and deliberately carrying TWO
    // decisions for the same item, which is what happens when the agent flags
    // an item and a human resolves it afterwards.
    private static final String ACTIVITY_JSON =
            """
            {"status":{"interval":"60s","lastActionAt":"2026-09-15T08:05:00.000Z",
                       "pending":1,"actioned":1,"high":1},
             "actions":[
               {"itemId":"8f1c2e5a-0000-4000-8000-000000000001","title":"Vendor NDA Template",
                "action":"flag","priority":"high","rationale":"Unusual indemnity clause.",
                "at":"2026-09-15T08:05:00.000Z"},
               {"itemId":"8f1c2e5a-0000-4000-8000-000000000001","title":"Vendor NDA Template",
                "action":"approve","priority":"low","rationale":"Looked routine.",
                "at":"2026-09-15T08:02:00.000Z"}
             ]}
            """;

    private HttpServer server;
    private final Map<String, String> authHeaders = new ConcurrentHashMap<>();
    private final List<String> pathsCalled = new ArrayList<>();
    private int itemsStatus = 200;
    private int flagStatus = 202;
    private final List<String> flagBodies = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/items", exchange -> {
            record(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, itemsStatus, itemsStatus == 200 ? ITEMS_JSON : "{\"error\":\"nope\"}");
        });
        server.createContext("/api/agent/activity", exchange -> {
            record(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, ACTIVITY_JSON);
        });
        server.createContext("/api/items/", exchange -> {
            record(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"));
            flagBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, flagStatus, flagStatus == 202 ? "{\"action\":\"flag\"}" : "{\"error\":\"no\"}");
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void record(String path, String authorization) {
        pathsCalled.add(path);
        if (authorization != null) {
            authHeaders.put(path, authorization);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private AnvilClient clientWith(String token, int pageSize) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new AnvilClient(
                new AnvilProperties(baseUrl, token, pageSize, Duration.ofSeconds(5)), new ObjectMapper());
    }

    @Test
    void readsAnvilsItemsAndItsTriagersDecisions() throws Exception {
        AnvilClient.Snapshot snapshot = clientWith(null, 50).read();

        assertThat(snapshot.items())
                .extracting(AnvilClient.AnvilItem::id, AnvilClient.AnvilItem::title, AnvilClient.AnvilItem::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "8f1c2e5a-0000-4000-8000-000000000001", "Vendor NDA Template", "flagged"),
                        org.assertj.core.groups.Tuple.tuple(
                                "8f1c2e5a-0000-4000-8000-000000000002", "Q1 Budget Proposal", "draft"));

        assertThat(snapshot.decisions()).hasSize(1);
        assertThat(snapshot.decisions().get("8f1c2e5a-0000-4000-8000-000000000001"))
                .satisfies(decision -> {
                    assertThat(decision.action()).isEqualTo("flag");
                    assertThat(decision.priority()).isEqualTo("high");
                    assertThat(decision.rationale()).isEqualTo("Unusual indemnity clause.");
                    assertThat(decision.at()).isNotNull();
                });
    }

    @Test
    void keepsTheNewestDecisionWhenAnItemWasDecidedTwice() throws Exception {
        // The feed is newest-first, so the FIRST entry for an item is current.
        // Taking the last would resurrect a superseded decision and make loom
        // argue with a call Anvil no longer stands behind.
        AnvilClient.Snapshot snapshot = clientWith(null, 50).read();

        assertThat(snapshot.decisions().get("8f1c2e5a-0000-4000-8000-000000000001").action())
                .isEqualTo("flag");
    }

    @Test
    void anUndecidedItemSimplyHasNoDecision() throws Exception {
        // This is the case loom cares about most: its deadline is running with
        // nothing to show for it.
        AnvilClient.Snapshot snapshot = clientWith(null, 50).read();

        assertThat(snapshot.decisions()).doesNotContainKey("8f1c2e5a-0000-4000-8000-000000000002");
    }

    @Test
    void honoursThePageSizeCap() throws Exception {
        assertThat(clientWith(null, 1).read().items()).hasSize(1);
    }

    @Test
    void sendsTheBearerTokenOnlyWhenOneIsConfigured() throws Exception {
        clientWith(null, 50).read();
        assertThat(authHeaders).as("no token configured, so no Authorization header").isEmpty();

        clientWith("s3cret", 50).read();
        assertThat(authHeaders.get("/api/items")).isEqualTo("Bearer s3cret");
    }

    @Test
    void reportsARefusalAsARefusalRatherThanAsAStatusCode() throws Exception {
        // 403 is the EXPECTED failure in a cell — Anvil resolves callers from
        // edge headers, not bearer tokens. "HTTP 403" alone sends people looking
        // in the wrong place, so the message names the cause.
        itemsStatus = 403;
        AnvilClient client = clientWith("s3cret", 50);

        assertThatThrownBy(client::read)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Anvil refused loom")
                .hasMessageContaining("edge headers");
    }

    @Test
    void reportsAnyOtherFailureWithItsStatus() throws Exception {
        itemsStatus = 503;
        AnvilClient client = clientWith(null, 50);

        assertThatThrownBy(client::read).isInstanceOf(IOException.class).hasMessageContaining("503");
    }

    @Test
    void flaggingAsksAnvilToPutTheItemBackInFrontOfAHuman() throws Exception {
        AnvilClient.PushBack result =
                clientWith(null, 50).flagItem("8f1c2e5a-0000-4000-8000-000000000001", "loom disputes this");

        assertThat(result.delivered()).isTrue();
        assertThat(pathsCalled).containsExactly("/api/items/8f1c2e5a-0000-4000-8000-000000000001/flag");
        assertThat(flagBodies).singleElement().satisfies(body -> assertThat(body).contains("loom disputes this"));
    }

    @Test
    void anAlreadyResolvedItemIsAnOutcomeNotAFailure() throws Exception {
        // Anvil settled it while loom was deliberating. Retrying would never
        // succeed, and the case timeline should say what happened rather than
        // show an error.
        flagStatus = 409;

        AnvilClient.PushBack result = clientWith(null, 50).flagItem("some-item", "loom disputes this");

        assertThat(result.delivered()).isFalse();
        assertThat(result.detail()).contains("already resolved");
    }

    @Test
    void aRefusedFlagReportsTheRealCause() throws Exception {
        flagStatus = 403;
        AnvilClient client = clientWith("s3cret", 50);

        assertThatThrownBy(() -> client.flagItem("some-item", "loom disputes this"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Anvil refused loom")
                .hasMessageContaining("edge headers");
    }

    @Test
    void callsBothEndpointsAndNoOthers() throws Exception {
        clientWith(null, 50).read();

        // Two calls per poll, not one per item: a per-item log fetch would make
        // every poll an N+1 against a neighbour.
        assertThat(pathsCalled).containsExactly("/api/items", "/api/agent/activity");
    }
}
