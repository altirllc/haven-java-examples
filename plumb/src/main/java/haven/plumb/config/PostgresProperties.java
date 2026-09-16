package haven.plumb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The tenant's PostgreSQL, from tenant-postgres-secret.
 *
 * Two databases, both on the same per-tenant instance at postgres:5432 — the
 * records database the app's tables live in, and the vectors database carrying
 * pgvector. The bundle composes both URLs from POSTGRES_RECORDS_DATABASE and
 * POSTGRES_VECTORS_DATABASE with the same credentials.
 */
@ConfigurationProperties(prefix = "postgres")
public record PostgresProperties(String url, String vectorsUrl, String username, String password, String schema) {

    public boolean configured() {
        return Values.allSet(url, username, password);
    }

    public boolean vectorsConfigured() {
        return Values.allSet(vectorsUrl, username, password);
    }
}
