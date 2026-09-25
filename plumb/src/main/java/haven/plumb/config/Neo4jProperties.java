package haven.plumb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The tenant's Neo4j, from tenant-neo4j-secret.
 *
 * Every Haven tenant is given Neo4j in the baseline stack, and at the time this
 * probe was written no catalog bundle consumed it — so a tenant's graph database
 * had never been verified by anything. That is exactly the shape of failure
 * plumb exists for: provisioned, believed working, never once connected to.
 */
@ConfigurationProperties(prefix = "neo4j")
public record Neo4jProperties(String uri, String username, String password, String database) {

    public boolean configured() {
        return Values.allSet(uri, username, password);
    }
}
