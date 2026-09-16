package haven.plumb.probe.impl;

import haven.plumb.config.SiblingsProperties;
import haven.plumb.probe.Probe;
import haven.plumb.probe.ProbeGroup;
import haven.plumb.probe.ProbeSource;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * One row per other app in this tenant.
 *
 * The distinction this makes is the reason it exists. A host that does not
 * RESOLVE means the app is not installed for this tenant — a fact, not a fault,
 * and reporting it red would make a correctly provisioned tenant look broken.
 * Anything else — refused, timed out, 500 — is a real failure of an app that is
 * supposed to be there. Anvil draws the same line for the same reason, and it
 * catches the JDK detail that makes it easy to get wrong: HttpClient wraps the
 * DNS miss in a ConnectException, so the UnresolvedAddressException has to be
 * looked for down the cause chain rather than caught at the top.
 */
@Component
public class SiblingProbes implements ProbeSource {

    private final SiblingsProperties properties;
    private final Http http;

    public SiblingProbes(SiblingsProperties properties, Http http) {
        this.properties = properties;
        this.http = http;
    }

    @Override
    public List<Probe> probes() {
        return properties.targets().stream()
                .filter(SiblingsProperties.Target::configured)
                .map(target -> (Probe) new SiblingProbe(target, http))
                .toList();
    }

    private record SiblingProbe(SiblingsProperties.Target target, Http http) implements Probe {

        @Override
        public String id() {
            return "sibling." + target.id();
        }

        @Override
        public ProbeGroup group() {
            return ProbeGroup.SIBLINGS;
        }

        @Override
        public String title() {
            return target.name();
        }

        @Override
        public String proves() {
            return "the app is installed in this tenant and answers in-cluster";
        }

        @Override
        public Outcome run() throws Exception {
            try {
                Http.Reply reply = http.get(target.url());
                if (!reply.ok()) {
                    return Outcome.fail("HTTP " + reply.status(), target.url(), reply.snippet());
                }
                return Outcome.ok("reachable", target.url(), reply.snippet());
            } catch (Exception failure) {
                if (notInstalled(failure)) {
                    return Outcome.skipped("not installed in this tenant (host does not resolve)");
                }
                throw failure;
            }
        }

        private boolean notInstalled(Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof UnresolvedAddressException || cause instanceof UnknownHostException) {
                    return true;
                }
                if (cause.getCause() == cause) {
                    break;
                }
            }
            return false;
        }
    }
}
