package haven.loom.api.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP resource-server config, from the tenant-zitadel-secret secret.
 *
 * Both blank disables MCP CLOSED — every request is denied — so local dev boots
 * without them rather than silently exposing an unauthenticated surface.
 */
@ConfigurationProperties(prefix = "zitadel")
public record McpProperties(String issuer, String mcpAudience) {

    public boolean configured() {
        return issuer != null && !issuer.isBlank() && mcpAudience != null && !mcpAudience.isBlank();
    }

    /** JWKS lives at the issuer's well-known keys path. */
    public String jwksUri() {
        return issuer + "/oauth/v2/keys";
    }
}
