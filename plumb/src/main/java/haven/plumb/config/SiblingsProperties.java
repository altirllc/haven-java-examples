package haven.plumb.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Other apps in this tenant, reached in-cluster.
 *
 * Apps address each other by SERVICE HOST, not by path prefix: Traefik strips
 * the /{app} prefix at the edge, so an in-cluster call carries no prefix at all.
 * Getting this wrong is the classic first-integration bug, so the defaults spell
 * the correct form out — http://{app}-api.default:3000/... — and a sibling row
 * proves both that the service exists and that the app behind it answers.
 */
@ConfigurationProperties(prefix = "siblings")
public record SiblingsProperties(List<Target> targets) {

    public SiblingsProperties {
        targets = targets == null ? List.of() : targets;
    }

    /**
     * @param id stable probe id suffix, so the metric label survives a rename
     * @param name what to call it on the page
     * @param url an endpoint that answers without authentication
     */
    public record Target(String id, String name, String url) {

        public boolean configured() {
            return Values.allSet(id, url);
        }
    }
}
