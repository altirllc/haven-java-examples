package haven.loom.persistence;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How long a case may sit unreviewed before it lapses.
 *
 * One value, deliberately: an SLA that varies per case would be a policy engine,
 * and loom has no evidence yet about what that policy should be. It is
 * configuration rather than a constant because the right answer differs between
 * a demo tenant and a real one — and because a test needs to make it small.
 *
 * It lives beside PlanProperties in persistence because BOTH deployables open
 * cases: the REST route in api, and ingest in jobs. jobs cannot depend on api.
 */
@Validated
@ConfigurationProperties(prefix = "cases")
public record CaseProperties(@NotNull Duration sla) {}
