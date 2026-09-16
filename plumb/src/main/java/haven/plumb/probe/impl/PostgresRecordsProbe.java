package haven.plumb.probe.impl;

import haven.plumb.config.PostgresProperties;
import haven.plumb.probe.Probe;
import haven.plumb.probe.ProbeGroup;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The records database: migrate, write, read back, clean up.
 *
 * Deliberately the full bootstrap a real app performs, not a SELECT 1. On a
 * fresh tenant the interesting failures all live in this sequence — the schema
 * does not exist, the role cannot create it, Liquibase cannot create its own
 * lock table, two pods race the same first migration. A connectivity check that
 * skips the migration proves the network and nothing an app depends on.
 *
 * The advisory lock around schema creation and the changelog is copied from the
 * agent scaffold for a reason documented there: CREATE SCHEMA IF NOT EXISTS is
 * itself unserialized in Postgres, so two sessions creating the same absent
 * schema both pass the check and collide on pg_namespace_nspname_index. plumb is
 * one replica today, but it shares the tenant database with apps doing exactly
 * this at their own boot.
 */
@Component
@Order(10)
public class PostgresRecordsProbe implements Probe {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    private final PostgresProperties properties;

    public PostgresRecordsProbe(PostgresProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return "postgres.records";
    }

    @Override
    public ProbeGroup group() {
        return ProbeGroup.DATA;
    }

    @Override
    public String title() {
        return "PostgreSQL — records";
    }

    @Override
    public String proves() {
        return "connect, own a schema, run migrations, write and read a row back";
    }

    @Override
    public Outcome run() throws Exception {
        if (!properties.configured()) {
            return Outcome.skipped(
                    "POSTGRES_DATABASE_URL / POSTGRES_USERNAME / POSTGRES_PASSWORD not set"
                            + " — composed by the bundle from tenant-postgres-secret");
        }

        int changesets = migrate();

        // A fresh connection every sweep, closed at the end: a pooled connection
        // proves the pool is warm, not that a cold app could connect right now.
        try (Connection connection = open()) {
            String server = serverVersion(connection);
            UUID token = UUID.randomUUID();
            Instant writtenAt = writeAndReadBack(connection, token);

            return Outcome.ok(
                    "schema " + properties.schema() + " migrated, round trip verified",
                    "server: " + server,
                    "changesets applied: " + changesets,
                    "heartbeat: " + token + " at " + writtenAt);
        }
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection(properties.url(), properties.username(), properties.password());
    }

    private String serverVersion(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT version()")) {
            // version() is verbose; the first two words are the part anyone reads.
            String full = resultSet.next() ? resultSet.getString(1) : "unknown";
            String[] words = full.split(" ");
            return words.length >= 2 ? words[0] + " " + words[1] : full;
        }
    }

    /**
     * Schema plus changelog under one advisory lock, on a connection of their
     * own. Returns the number of changesets in the changelog.
     *
     * The dedicated connection is not tidiness. Closing a Liquibase closes the
     * Database it wraps, which closes the JDBC connection underneath it — so a
     * migration sharing the probe's connection leaves everything after it
     * talking to a closed socket. There is no explicit unlock either: a pg
     * advisory lock is session-scoped and releases when the connection closes,
     * which is the same property that makes it safe when a holder crashes
     * mid-migration.
     */
    private int migrate() throws Exception {
        // String.hashCode is specified, so every process derives the same key.
        long lockKey = "plumb:liquibase".hashCode();
        try (Connection connection = open()) {
            try (Statement lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_lock(" + lockKey + ")");
                lock.execute("CREATE SCHEMA IF NOT EXISTS " + properties.schema());
            }
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setDefaultSchemaName(properties.schema());
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
                return liquibase.getDatabaseChangeLog().getChangeSets().size();
            }
        }
    }

    /**
     * Write a row, read it back, delete it. The delete is not tidiness: leaving
     * a row per sweep would grow the tenant's database forever for no diagnostic
     * value, and the table's purpose is to prove the write path, not to keep
     * history.
     */
    private Instant writeAndReadBack(Connection connection, UUID token) throws Exception {
        String table = properties.schema() + ".heartbeat";
        try (PreparedStatement insert =
                connection.prepareStatement("INSERT INTO " + table + " (id, note) VALUES (?, ?)")) {
            insert.setObject(1, token);
            insert.setString(2, "plumb round trip");
            insert.executeUpdate();
        }
        Instant writtenAt;
        try (PreparedStatement select =
                connection.prepareStatement("SELECT written_at FROM " + table + " WHERE id = ?")) {
            select.setObject(1, token);
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("row " + token + " was written but did not read back");
                }
                writtenAt = resultSet.getTimestamp(1).toInstant();
            }
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            delete.setObject(1, token);
            delete.executeUpdate();
        }
        return writtenAt;
    }
}
