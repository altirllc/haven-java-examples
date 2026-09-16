package haven.plumb.probe;

import java.util.List;

/**
 * A bean that contributes probes decided at runtime.
 *
 * Exists for one case: the sibling apps, whose number and names come from
 * configuration, so they cannot each be a @Bean. Everything with a fixed
 * identity is an ordinary @Component implementing {@link Probe} — reach for this
 * only when the set genuinely is not known until the config is bound.
 */
public interface ProbeSource {

    List<Probe> probes();
}
