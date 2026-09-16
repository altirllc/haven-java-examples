package haven.plumb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The tenant's identity provider, from tenant-zitadel-secret.
 *
 * plumb authenticates nothing — it only checks that the issuer an app would
 * validate tokens against is reachable and publishes the keys it claims to. That
 * is the failure worth catching early: an app with a wrong or unreachable issuer
 * denies every MCP request at runtime while looking perfectly healthy.
 */
@ConfigurationProperties(prefix = "zitadel")
public record ZitadelProperties(String issuer, String mcpAudience) {

    public boolean configured() {
        return Values.isSet(issuer);
    }
}
