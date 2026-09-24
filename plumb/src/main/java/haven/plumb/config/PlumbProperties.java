package haven.plumb.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * plumb's own settings — the only block here that is not a seam.
 *
 * Note what is missing: no @Validated, no @NotBlank. Every seam record in this
 * package is the same, and it is the whole design. The agent scaffold validates
 * its config so a missing variable kills the process at boot; plumb exists to
 * report that missing variable, so it must survive it.
 */
@ConfigurationProperties(prefix = "plumb")
public record PlumbProperties(
        /** The tenant this instance belongs to, from the pod label haven.tenant. */
        String tenantId,
        /** How often the sweep runs on its own. */
        Duration interval,
        /** How long a whole sweep may take before the stragglers are called failed. */
        Duration probeTimeout,
        /** Grace before the first sweep, so the page is already populated on the first view. */
        Duration startupDelay) {}
