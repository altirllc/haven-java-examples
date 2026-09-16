package haven.plumb.probe;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The sweep runs itself.
 *
 * initialDelay covers the boot case — by the time anyone opens the page it is
 * already populated, and in a cell the first sweep lands in the logs and the
 * metrics without anyone asking. fixedDelay rather than fixedRate: sweeps must
 * not overlap, and a slow seam should push the next sweep back rather than
 * stack a second one on top of it.
 */
@Component
public class ProbeSchedule {

    private final ProbeRegistry registry;

    public ProbeSchedule(ProbeRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(initialDelayString = "${plumb.startup-delay}", fixedDelayString = "${plumb.interval}")
    public void sweep() {
        registry.runAll();
    }
}
