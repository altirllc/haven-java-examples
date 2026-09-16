package haven.loom.jobs;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How often loom polls Anvil.
 *
 * Its own cadence, separate from the agent's sweep: polling is two HTTP calls
 * and should be frequent, while a review sweep is a series of model calls and
 * should not be. The gap between an item appearing in Anvil and a case existing
 * for it is this interval — and the SLA clock only starts once the case exists.
 */
@Validated
@ConfigurationProperties(prefix = "ingest")
public record IngestProperties(@NotNull Duration interval) {}
