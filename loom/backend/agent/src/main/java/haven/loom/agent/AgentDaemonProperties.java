package haven.loom.agent;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The daemon's cadence, from AGENT_INTERVAL.
 *
 * Lives in :agent rather than :jobs because both deployables need it: :jobs
 * drives the loop with it, and :api reports it through the agent-status tool and
 * GET /api/agent/config.
 */
@Validated
@ConfigurationProperties(prefix = "agent")
public record AgentDaemonProperties(@NotNull Duration interval) {

    /** Open cases reviewed per sweep, nearest deadline first. */
    public static final int BATCH_SIZE = 10;

    /** Human-readable form for the agent's prompt and the config endpoint. */
    public String display() {
        return interval.toMinutes() > 0 && interval.toSecondsPart() == 0
                ? interval.toMinutes() + "m"
                : interval.toSeconds() + "s";
    }
}
