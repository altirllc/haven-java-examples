package haven.plumb.probe.impl;

import haven.plumb.config.PlumbProperties;
import haven.plumb.config.Values;
import haven.plumb.probe.Probe;
import haven.plumb.probe.ProbeGroup;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Who this pod thinks it is.
 *
 * The tenant id does not arrive in a secret — it is read off the pod LABEL
 * haven.tenant through the downward API, which is a different failure mode from
 * every other seam here: the value is silently empty when the bundle omits the
 * fieldRef, and an app that logs an empty tenant, names an empty Temporal
 * namespace and writes to an empty S3 prefix will look healthy while being
 * anonymous. This row is the cheapest one on the page and catches that.
 */
@Component
@Order(10)
public class IdentityProbe implements Probe {

    private final PlumbProperties properties;
    private final Environment environment;

    public IdentityProbe(PlumbProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public String id() {
        return "identity";
    }

    @Override
    public ProbeGroup group() {
        return ProbeGroup.IDENTITY;
    }

    @Override
    public String title() {
        return "Tenant identity";
    }

    @Override
    public String proves() {
        return "the pod knows which tenant it belongs to, from the haven.tenant label";
    }

    @Override
    public Outcome run() {
        String profiles = String.join(",", environment.getActiveProfiles());
        String evidenceProfiles = "profiles: " + (profiles.isBlank() ? "(none)" : profiles);
        String java = "java: " + Runtime.version();
        String host = "host: " + hostname();

        if (!Values.isSet(properties.tenantId())) {
            return Outcome.skipped("HAVEN_TENANT_ID is not set — expected from the pod label haven.tenant");
        }
        return Outcome.ok("tenant " + properties.tenantId(), evidenceProfiles, java, host);
    }

    private String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException unresolvable) {
            // Not worth failing a row over: in a cell the hostname is the pod
            // name, which is context, not a seam.
            return "unknown";
        }
    }
}
