package haven.loom.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.core.io.ResourceLoader;

/**
 * Runs a Liquibase changelog under a Postgres advisory lock.
 *
 * Liquibase serializes CHANGESETS via its DATABASECHANGELOGLOCK table, but
 * creating that table (and DATABASECHANGELOG) is itself unserialized: two
 * processes booting against a fresh database — api and jobs start together in
 * the same pod on every new tenant — both see no table, both CREATE, and one
 * dies with `duplicate key value violates unique constraint
 * "pg_type_typname_nsp_index"`. An advisory lock serializes the whole
 * bootstrap instead. The lock is
 * session-scoped and app-named, so concurrent apps sharing the tenant database
 * queue instead of colliding, and a crashed holder releases on disconnect.
 *
 * Schema creation lives HERE, under the same lock, and not in Hikari's
 * connection-init-sql: CREATE SCHEMA IF NOT EXISTS is itself unserialized in
 * Postgres — two sessions creating the same absent schema both pass the check
 * and collide on pg_namespace_nspname_index at pool startup, before any
 * migration code runs.
 */
public final class LockedLiquibase {

    private LockedLiquibase() {}

    public static void migrate(
            DataSource dataSource,
            ResourceLoader resourceLoader,
            String changeLog,
            String defaultSchema,
            String lockName)
            throws SQLException, LiquibaseException {
        // String.hashCode is specified, so every process derives the same key.
        long key = lockName.hashCode();
        try (Connection lockConnection = dataSource.getConnection();
                Statement lock = lockConnection.createStatement()) {
            lock.execute("SELECT pg_advisory_lock(" + key + ")");
            try {
                if (defaultSchema != null) {
                    lock.execute("CREATE SCHEMA IF NOT EXISTS " + defaultSchema);
                }
                SpringLiquibase liquibase = new SpringLiquibase();
                liquibase.setDataSource(dataSource);
                liquibase.setChangeLog(changeLog);
                if (defaultSchema != null) {
                    liquibase.setDefaultSchema(defaultSchema);
                }
                liquibase.setResourceLoader(resourceLoader);
                liquibase.afterPropertiesSet();
            } finally {
                lock.execute("SELECT pg_advisory_unlock(" + key + ")");
            }
        }
    }
}
