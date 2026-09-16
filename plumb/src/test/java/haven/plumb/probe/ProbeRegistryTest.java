package haven.plumb.probe;

import static org.assertj.core.api.Assertions.assertThat;

import haven.plumb.config.PlumbProperties;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fail-open contract, pinned.
 *
 * These are the tests worth having in this app. plumb's value rests entirely on
 * one promise — no seam can take it down — and that promise lives in
 * ProbeRegistry. Everything else here is a round trip against a real service,
 * which a unit test cannot honestly simulate and should not pretend to.
 */
class ProbeRegistryTest {

    private static final PlumbProperties SETTINGS =
            new PlumbProperties("test", Duration.ofMinutes(1), Duration.ofSeconds(2), Duration.ofSeconds(5), true);

    private static ProbeRegistry registryOf(Probe... probes) {
        return new ProbeRegistry(List.of(probes), List.of(), SETTINGS);
    }

    @Test
    void a_probe_that_throws_becomes_a_failed_row() {
        ProbeRegistry registry = registryOf(new StubProbe("boom", () -> {
            throw new SQLException("password authentication failed", null, 0, new IllegalStateException("pool closed"));
        }));

        List<ProbeResult> results = registry.runAll();

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(ProbeStatus.FAIL);
            // The whole cause chain, because the top exception is rarely the answer.
            assertThat(result.detail())
                    .contains("password authentication failed")
                    .contains("pool closed");
        });
    }

    @Test
    void a_probe_that_hangs_becomes_a_failed_row_instead_of_stalling_the_sweep() {
        ProbeRegistry registry = registryOf(new StubProbe("slow", () -> {
            Thread.sleep(Duration.ofMinutes(5));
            return Probe.Outcome.ok("never gets here");
        }));

        List<ProbeResult> results = registry.runAll();

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(ProbeStatus.FAIL);
            assertThat(result.detail()).contains("timed out");
        });
    }

    @Test
    void one_broken_probe_does_not_stop_the_others() {
        ProbeRegistry registry = registryOf(
                new StubProbe("broken", () -> {
                    throw new IllegalStateException("down");
                }),
                new StubProbe("fine", () -> Probe.Outcome.ok("round trip verified")));

        registry.runAll();

        assertThat(registry.summary().ok()).isEqualTo(1);
        assertThat(registry.summary().failed()).isEqualTo(1);
        assertThat(registry.summary().healthy()).isFalse();
    }

    @Test
    void an_unconfigured_seam_is_skipped_and_leaves_the_registry_healthy() {
        ProbeRegistry registry = registryOf(new StubProbe("absent", () -> Probe.Outcome.skipped("SOME_VAR not set")));

        registry.runAll();

        assertThat(registry.summary().skipped()).isEqualTo(1);
        // Skipped is not a problem — a tenant that does not use a seam is not broken.
        assertThat(registry.summary().healthy()).isTrue();
    }

    @Test
    void results_are_available_before_anything_has_run() {
        ProbeRegistry registry = registryOf(new StubProbe("later", () -> Probe.Outcome.ok("fine")));

        // The page must render on the first request, before the first sweep.
        assertThat(registry.results()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(ProbeStatus.SKIPPED);
            assertThat(result.detail()).isEqualTo("not run yet");
        });
    }

    private interface Body {
        Probe.Outcome run() throws Exception;
    }

    private record StubProbe(String id, Body body) implements Probe {

        @Override
        public ProbeGroup group() {
            return ProbeGroup.DATA;
        }

        @Override
        public String title() {
            return id;
        }

        @Override
        public String proves() {
            return "nothing, it is a stub";
        }

        @Override
        public Outcome run() throws Exception {
            return body.run();
        }
    }
}
