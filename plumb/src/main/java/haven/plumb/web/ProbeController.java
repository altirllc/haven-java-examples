package haven.plumb.web;

import haven.plumb.probe.ProbeRegistry;
import haven.plumb.probe.ProbeResult;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The machine-readable surface. Everything the page shows is here first — the
 * page is a rendering of this, not a separate source of truth.
 *
 * GET returns the last known results without touching any seam, so polling this
 * is cheap and a monitor cannot accidentally hammer the tenant's database. POST
 * is the explicit "go and check now".
 */
@RestController
@RequestMapping("/api/checks")
public class ProbeController {

    private final ProbeRegistry registry;

    public ProbeController(ProbeRegistry registry) {
        this.registry = registry;
    }

    public record ChecksResponse(ProbeRegistry.Summary summary, List<ProbeResult> results) {}

    /** Last known results. Does not run anything. */
    @GetMapping
    public ChecksResponse checks() {
        return new ChecksResponse(registry.summary(), registry.results());
    }

    /** Run every probe now and return the fresh results. */
    @PostMapping("/run")
    public ChecksResponse runAll() {
        registry.runAll();
        return new ChecksResponse(registry.summary(), registry.results());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProbeResult> check(@PathVariable String id) {
        return registry.result(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound()
                .build());
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<ProbeResult> run(@PathVariable String id) {
        return registry.runOne(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound()
                .build());
    }
}
