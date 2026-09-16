package haven.loom.persistence;

import haven.loom.domain.Plan;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The pricing plan, from the bundle's PLAN / PLAN_FREE_CASES. `free` refuses
 * new items once the allowance is spent; `paid` continues and the excess is
 * what gets billed.
 */
@Validated
@ConfigurationProperties(prefix = "plan")
public record PlanProperties(@NotNull Plan name, @Positive int freeCases) {
}
