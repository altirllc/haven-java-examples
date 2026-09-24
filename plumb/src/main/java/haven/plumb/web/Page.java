package haven.plumb.web;

import haven.plumb.config.PlumbProperties;
import haven.plumb.config.Values;
import haven.plumb.probe.ProbeRegistry;
import haven.plumb.probe.ProbeResult;
import haven.plumb.probe.ProbeStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The whole UI: one server-rendered page, no build step, no JavaScript.
 *
 * Deliberate. plumb is the thing you open when a tenant is broken, and a React
 * bundle is one more moving part that can be the reason you cannot see why. A
 * single HTML response also means the page works from curl, from a pod exec, and
 * through a port-forward with no asset paths to get wrong.
 */
@Component
public class Page {

    private static final String CSS =
            """
            :root {
              color-scheme: light dark;
              --bg: #fbfbfa; --panel: #ffffff; --line: #e4e2dd;
              --ink: #1c1b19; --muted: #6b6862;
              --ok: #1a7f4b; --ok-bg: #e7f4ec;
              --fail: #b4232b; --fail-bg: #fbeaea;
              --skip: #6b6862; --skip-bg: #f0efec;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #16161a; --panel: #1d1d22; --line: #2e2e35;
                --ink: #eceae6; --muted: #9a968e;
                --ok: #5fd39a; --ok-bg: #16301f;
                --fail: #ff8a86; --fail-bg: #33181a;
                --skip: #9a968e; --skip-bg: #26262c;
              }
            }
            * { box-sizing: border-box; }
            body {
              margin: 0; padding: 2rem 1.25rem 4rem;
              background: var(--bg); color: var(--ink);
              font: 15px/1.55 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
            }
            main { max-width: 60rem; margin: 0 auto; }
            header { display: flex; flex-wrap: wrap; align-items: baseline; gap: .75rem 1.25rem; margin-bottom: .25rem; }
            h1 { font-size: 1.5rem; margin: 0; letter-spacing: -.01em; }
            h1 span { color: var(--muted); font-weight: 400; }
            .tagline { color: var(--muted); margin: 0 0 1.5rem; max-width: 48rem; }
            .bar { display: flex; flex-wrap: wrap; align-items: center; gap: .5rem 1rem;
                   padding: .75rem 1rem; margin-bottom: 2rem;
                   background: var(--panel); border: 1px solid var(--line); border-radius: 10px; }
            .count { font-variant-numeric: tabular-nums; }
            .count b { font-size: 1.05rem; }
            .spacer { flex: 1 1 auto; }
            button { font: inherit; font-weight: 500; cursor: pointer;
                     padding: .4rem .9rem; border-radius: 7px;
                     border: 1px solid var(--line); background: var(--bg); color: var(--ink); }
            button:hover { border-color: var(--muted); }
            h2 { font-size: .8rem; text-transform: uppercase; letter-spacing: .08em;
                 color: var(--muted); margin: 2rem 0 .6rem; font-weight: 600; }
            .row { display: grid; grid-template-columns: 5.5rem 1fr auto; gap: 0 1rem;
                   padding: .85rem 1rem; background: var(--panel);
                   border: 1px solid var(--line); border-radius: 10px; margin-bottom: .5rem; }
            .pill { align-self: start; text-align: center; font-size: .7rem; font-weight: 700;
                    letter-spacing: .06em; padding: .25rem 0; border-radius: 5px; }
            .ok { color: var(--ok); background: var(--ok-bg); }
            .fail { color: var(--fail); background: var(--fail-bg); }
            .skipped { color: var(--skip); background: var(--skip-bg); }
            .title { font-weight: 600; }
            .proves, .detail, .latency { color: var(--muted); font-size: .875rem; }
            .detail { margin-top: .3rem; color: var(--ink); }
            .row.fail .detail { color: var(--fail); }
            .latency { font-variant-numeric: tabular-nums; white-space: nowrap; }
            ul { list-style: none; margin: .5rem 0 0; padding: 0;
                 font: .8rem/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; color: var(--muted); }
            li::before { content: "- "; }
            footer { color: var(--muted); font-size: .8rem; margin-top: 3rem; }
            """;

    private final ProbeRegistry registry;
    private final PlumbProperties properties;

    public Page(ProbeRegistry registry, PlumbProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * @param basePath where the page lives as the browser sees it, from
     *     {@link BasePath}. It anchors the form's relative action, so "Run all"
     *     posts inside the app whether or not the URL has a trailing slash.
     */
    public String render(String basePath) {
        ProbeRegistry.Summary summary = registry.summary();
        StringBuilder html = new StringBuilder(8_192);

        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<base href=\"")
                .append(escape(basePath))
                .append("\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>plumb")
                .append(Values.isSet(properties.tenantId()) ? " &middot; " + escape(properties.tenantId()) : "")
                .append("</title><style>")
                .append(CSS)
                .append("</style></head><body><main>");

        html.append("<header><h1>plumb <span>")
                .append(Values.isSet(properties.tenantId()) ? escape(properties.tenantId()) : "no tenant")
                .append("</span></h1></header>")
                .append("<p class=\"tagline\">Every seam a Haven application is wired to, exercised with a real")
                .append(" round trip. Skipped means not configured here, which is not a problem;")
                .append(" red is.</p>");

        html.append("<div class=\"bar\">")
                .append("<span class=\"count\"><b>")
                .append(summary.ok())
                .append("</b> ok</span>")
                .append("<span class=\"count\"><b>")
                .append(summary.failed())
                .append("</b> failed</span>")
                .append("<span class=\"count\"><b>")
                .append(summary.skipped())
                .append("</b> skipped</span>")
                .append("<span class=\"proves\">last run ")
                .append(summary.lastRun() == null ? "never" : ago(summary.lastRun()))
                .append("</span><span class=\"spacer\"></span>")
                .append("<form method=\"post\" action=\"run\"><button type=\"submit\">Run all</button></form>")
                .append("</div>");

        for (Map.Entry<String, List<ProbeResult>> group :
                registry.grouped().entrySet()) {
            html.append("<h2>").append(escape(group.getKey())).append("</h2>");
            for (ProbeResult result : group.getValue()) {
                appendRow(html, result);
            }
        }

        html.append("<footer>Sweeps every ")
                .append(human(properties.interval()))
                .append(". JSON at <code>/api/checks</code>, metrics at <code>/actuator/prometheus</code>.")
                .append("</footer></main></body></html>");
        return html.toString();
    }

    private void appendRow(StringBuilder html, ProbeResult result) {
        String status = result.status().name().toLowerCase();
        html.append("<div class=\"row ").append(status).append("\" id=\"").append(escape(result.id())).append("\">");
        html.append("<span class=\"pill ").append(status).append("\">").append(status).append("</span>");
        html.append("<div><div class=\"title\">").append(escape(result.title())).append("</div>");
        html.append("<div class=\"proves\">").append(escape(result.proves())).append("</div>");
        html.append("<div class=\"detail\">").append(escape(result.detail())).append("</div>");
        if (!result.evidence().isEmpty()) {
            html.append("<ul>");
            for (String line : result.evidence()) {
                html.append("<li>").append(escape(line)).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("</div>");
        // Latency is meaningless for a probe that never ran or was skipped.
        html.append("<div class=\"latency\">")
                .append(result.status() == ProbeStatus.SKIPPED ? "" : result.latencyMs() + " ms")
                .append("</div>");
        html.append("</div>");
    }

    private String ago(Instant moment) {
        long seconds = Duration.between(moment, Instant.now()).toSeconds();
        if (seconds < 2) {
            return "just now";
        }
        if (seconds < 90) {
            return seconds + "s ago";
        }
        return Duration.between(moment, Instant.now()).toMinutes() + "m ago";
    }

    private String human(Duration duration) {
        return duration.toSeconds() < 60 ? duration.toSeconds() + "s" : duration.toMinutes() + "m";
    }

    /**
     * Escapes into element text and double-quoted attributes. Everything on this
     * page ultimately comes from configuration and from exception messages, and
     * an exception message is arbitrary text from a remote system — exactly the
     * thing that should never be concatenated into markup unescaped.
     */
    private String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
