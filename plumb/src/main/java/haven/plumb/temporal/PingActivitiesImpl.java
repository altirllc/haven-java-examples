package haven.plumb.temporal;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Runs in the ordinary worker process, not the workflow sandbox — which is why
 * it is allowed to touch the host at all.
 *
 * Stamping the hostname is the point: it turns "a workflow completed" into
 * "THIS process polled the task queue, picked the task up and ran it". Without
 * it a green row could equally mean some other app's worker is listening on our
 * task queue, which is a real misconfiguration and one worth catching.
 */
public class PingActivitiesImpl implements PingActivities {

    @Override
    public String echo(String token) {
        return token + "@" + hostname();
    }

    private String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException unresolvable) {
            return "unknown-host";
        }
    }
}
