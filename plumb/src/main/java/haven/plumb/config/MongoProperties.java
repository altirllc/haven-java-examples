package haven.plumb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The tenant's MongoDB, from tenant-mongodb-secret.
 *
 * Bound from our own key rather than spring.mongodb.* on purpose: there is no
 * spring-boot-starter-data-mongodb here, so nothing autoconfigures a client that
 * would quietly default to localhost/test and make an absent URI look like a
 * working connection to the wrong database.
 */
@ConfigurationProperties(prefix = "mongo")
public record MongoProperties(String uri, String collection) {

    public boolean configured() {
        return Values.isSet(uri);
    }
}
