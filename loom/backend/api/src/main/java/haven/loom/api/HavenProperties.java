package haven.loom.api;

import haven.loom.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tenant identity, from the haven.tenant pod label.
 *
 * Used for logging, the /api/me response, and the MCP token's org check — never
 * for database queries. Postgres already runs inside the tenant's own vCluster,
 * so it is tenant-scoped by construction and there is no tenant_id column.
 *
 * user/role are the local dev identity, read only when the edge sends no
 * headers (see IdentityResolver).
 */
@Validated
@ConfigurationProperties(prefix = "haven")
public record HavenProperties(@NotBlank String tenantId, @NotBlank String user, @NotNull Role role) {
}
