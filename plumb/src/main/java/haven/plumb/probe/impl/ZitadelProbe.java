package haven.plumb.probe.impl;

import haven.plumb.config.Values;
import haven.plumb.config.ZitadelProperties;
import haven.plumb.probe.Probe;
import haven.plumb.probe.ProbeGroup;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The identity provider: discovery resolves, and the keys an app would verify
 * tokens against are actually published.
 *
 * plumb authenticates nothing. It checks the two things an app silently depends
 * on and never tests: that the configured issuer serves an OIDC discovery
 * document, and that the document's issuer claim MATCHES the configured value.
 * A mismatch there is the subtle one — every token validation fails on the
 * issuer check while the URL looks perfectly reachable, so an app denies every
 * MCP request and reports itself healthy the whole time.
 */
@Component
@Order(20)
public class ZitadelProbe implements Probe {

    private final ZitadelProperties properties;
    private final Http http;
    private final ObjectMapper json;

    public ZitadelProbe(ZitadelProperties properties, Http http, ObjectMapper json) {
        this.properties = properties;
        this.http = http;
        this.json = json;
    }

    @Override
    public String id() {
        return "identity.zitadel";
    }

    @Override
    public ProbeGroup group() {
        return ProbeGroup.PLATFORM;
    }

    @Override
    public String title() {
        return "Identity (Zitadel)";
    }

    @Override
    public String proves() {
        return "the issuer serves discovery, agrees with its own name, and publishes signing keys";
    }

    @Override
    public Outcome run() throws Exception {
        if (!properties.configured()) {
            return Outcome.skipped("ZITADEL_ISSUER not set — from tenant-zitadel-secret, optional for an app");
        }

        String issuer = properties.issuer().replaceAll("/+$", "");
        Http.Reply discovery = http.get(issuer + "/.well-known/openid-configuration");
        if (!discovery.ok()) {
            return Outcome.fail("discovery returned " + discovery.status(), discovery.snippet());
        }

        JsonNode document = json.readTree(discovery.body());
        String declared = document.path("issuer").asString("");
        String jwksUri = document.path("jwks_uri").asString("");

        if (!issuer.equals(declared.replaceAll("/+$", ""))) {
            return Outcome.fail(
                    "the issuer calls itself " + declared + ", not " + issuer,
                    "token validation compares this exactly — every verification would fail");
        }
        if (jwksUri.isBlank()) {
            return Outcome.fail("discovery published no jwks_uri");
        }

        Http.Reply keys = http.get(jwksUri);
        if (!keys.ok()) {
            return Outcome.fail("jwks_uri returned " + keys.status(), jwksUri);
        }
        int published = json.readTree(keys.body()).path("keys").size();
        if (published == 0) {
            return Outcome.fail("the JWKS is empty — nothing could verify a token", jwksUri);
        }

        String audience = Values.isSet(properties.mcpAudience())
                ? "mcp audience: " + properties.mcpAudience()
                : "mcp audience: not set (MCP would deny closed)";
        return Outcome.ok(
                "issuer reachable and publishing " + published + " signing key(s)",
                "issuer: " + issuer,
                "jwks: " + jwksUri,
                audience);
    }
}
