package haven.loom.jobs;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where Anvil is, and how loom authenticates to it.
 *
 * `apiUrl` carries no `/anvil` prefix: apps address each other by SERVICE HOST
 * in the tenant vCluster, and Traefik strips the prefix at the edge, so an
 * in-cluster call never has one. Getting this wrong is the classic first
 * integration bug.
 *
 * `token` is a Bearer credential and may be blank. Two things about it are
 * worth knowing before you debug a 403:
 *
 *  - Anvil's REST API does not read it. It resolves the caller from the
 *    `X-Auth-Request-*` headers its edge sets, so a bearer token is ignored and
 *    an in-cluster call is anonymous. Locally that is fine (Anvil run from
 *    source mints a dev identity); in a cell it is a 403, and the fix is a
 *    platform-side one — see haven/SECURITY-edge-header-trust.md and
 *    loom/L0-FINDINGS.md.
 *  - loom deliberately does NOT send `X-Auth-Request-*` itself. Those headers
 *    are forgeable by anything that can reach the Service, which is the finding
 *    above; building on it would make loom part of the problem.
 *
 * Nothing here is boot-fatal beyond being well-formed: Anvil is the one
 * dependency loom cannot control, and loom must still serve the cases it
 * already has when Anvil is down.
 */
@Validated
@ConfigurationProperties(prefix = "anvil")
public record AnvilProperties(
        @NotBlank String apiUrl, String token, @Positive int pageSize, Duration timeout) {

    public boolean authenticated() {
        return token != null && !token.isBlank();
    }
}
