package haven.plumb.web;

import static org.assertj.core.api.Assertions.assertThat;

import haven.plumb.config.PlumbProperties;
import haven.plumb.probe.Probe;
import haven.plumb.probe.ProbeGroup;
import haven.plumb.probe.ProbeRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageTest {

    private static final PlumbProperties SETTINGS =
            new PlumbProperties("acme", Duration.ofMinutes(1), Duration.ofSeconds(2), Duration.ofSeconds(5), true);

    @Test
    void a_hostile_failure_message_cannot_inject_markup() {
        // Not hypothetical: every detail on the page is either configuration or
        // an exception message, and an exception message is arbitrary text
        // returned by a remote system.
        ProbeRegistry registry = registryOf(() -> Probe.Outcome.fail("<script>alert(1)</script>", "a\"b"));
        registry.runAll();

        String html = new Page(registry, SETTINGS).render("/");

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("a&quot;b");
    }

    @Test
    void the_page_renders_before_the_first_sweep() {
        ProbeRegistry registry = registryOf(() -> Probe.Outcome.ok("fine"));

        String html = new Page(registry, SETTINGS).render("/");

        assertThat(html).contains("acme").contains("not run yet").contains("last run never");
    }

    @Test
    void run_all_posts_inside_the_app_whatever_url_the_page_was_opened_at() {
        // Opened at /plumb (no trailing slash), a bare relative action resolves
        // to /run — outside the app. The base anchors it to /plumb/run.
        String html = new Page(registryOf(() -> Probe.Outcome.ok("fine")), SETTINGS).render("/plumb/");

        assertThat(html).contains("<base href=\"/plumb/\">").contains("action=\"run\"");
    }

    private ProbeRegistry registryOf(Body body) {
        Probe probe = new Probe() {
            @Override
            public String id() {
                return "stub";
            }

            @Override
            public ProbeGroup group() {
                return ProbeGroup.DATA;
            }

            @Override
            public String title() {
                return "Stub";
            }

            @Override
            public String proves() {
                return "nothing";
            }

            @Override
            public Outcome run() {
                return body.run();
            }
        };
        return new ProbeRegistry(List.of(probe), List.of(), SETTINGS);
    }

    private interface Body {
        Probe.Outcome run();
    }
}
