package haven.plumb.probe.impl;

import haven.plumb.config.ModelsGatewayProperties;
import haven.plumb.config.PostgresProperties;
import haven.plumb.probe.Probe;
import haven.plumb.probe.ProbeGroup;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.StringJoiner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The vectors database: pgvector present, and a nearest-neighbour query that
 * actually returns the nearer vector.
 *
 * A second database, not a second schema — the tenant's Postgres ships a
 * separate `vectors` database and the two URLs differ only in the database name.
 * That detail is worth a row of its own because of a specific trap the agent
 * scaffold documents: the moment an app declares any DataSource bean, Boot backs
 * off and spring.datasource.* is ignored, so a lone vectors datasource silently
 * sends the ENTIRE app — migrations, entities, chat memory — into the vectors
 * database while every test stays green. If these two rows disagree about which
 * database they reached, that is the bug.
 *
 * The dimension is not arbitrary: the platform standardised on 1024 so one
 * embedding model serves every app, and a store built for a different width
 * rejects the gateway's output at the first write.
 */
@Component
@Order(20)
public class PostgresVectorsProbe implements Probe {

    private final PostgresProperties properties;
    private final ModelsGatewayProperties models;

    public PostgresVectorsProbe(PostgresProperties properties, ModelsGatewayProperties models) {
        this.properties = properties;
        this.models = models;
    }

    @Override
    public String id() {
        return "postgres.vectors";
    }

    @Override
    public ProbeGroup group() {
        return ProbeGroup.DATA;
    }

    @Override
    public String title() {
        return "PostgreSQL — vectors (pgvector)";
    }

    @Override
    public String proves() {
        return "the vectors database exists, carries pgvector, and answers a kNN query at the platform width";
    }

    @Override
    public Outcome run() throws Exception {
        if (!properties.vectorsConfigured()) {
            return Outcome.skipped(
                    "POSTGRES_VECTORS_URL not set — composed by the bundle from"
                            + " tenant-postgres-secret POSTGRES_VECTORS_DATABASE");
        }

        try (Connection connection =
                DriverManager.getConnection(properties.vectorsUrl(), properties.username(), properties.password())) {

            String database = connection.getCatalog();
            String extension = extensionVersion(connection);
            if (extension == null) {
                // Deliberately not CREATE EXTENSION: that needs rights the app
                // role is not meant to have, and installing it is the postgres
                // element's job. Saying so is more useful than half-fixing it.
                return Outcome.fail(
                        "the vector extension is not installed in database " + database,
                        "install is owned by the postgres element, not by the app");
            }

            int dimensions = models.embeddingDimensions();
            int nearest = nearestNeighbour(connection, dimensions);
            if (nearest != 1) {
                return Outcome.fail("kNN query returned row " + nearest + ", expected the nearer vector (1)");
            }

            return Outcome.ok(
                    "pgvector " + extension + " answered a " + dimensions + "-dimension kNN query",
                    "database: " + database,
                    "distinct from records database: " + !database.equals(recordsDatabase()));
        }
    }

    private String recordsDatabase() {
        String url = properties.url();
        if (url == null) {
            return "";
        }
        int lastSlash = url.lastIndexOf('/');
        return lastSlash < 0 ? url : url.substring(lastSlash + 1);
    }

    private String extensionVersion(Connection connection) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT extversion FROM pg_extension WHERE extname = 'vector'");
                ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    /**
     * Two unit vectors one axis apart, then ask which is closer to the first.
     * A TEMP table keeps this out of the tenant's schema entirely and is dropped
     * when the session ends — which is when this probe closes the connection —
     * so there is nothing to clean up and nothing to leak. Note the absence of
     * ON COMMIT DROP: the connection is in autocommit, so that clause would drop
     * the table on the CREATE statement's own implicit commit.
     */
    private int nearestNeighbour(Connection connection, int dimensions) throws Exception {
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE TEMP TABLE plumb_probe_vectors (id INT PRIMARY KEY, embedding VECTOR(" + dimensions
                    + "))");
        }
        String first = unitVector(dimensions, 0);
        String second = unitVector(dimensions, 1);
        try (PreparedStatement insert =
                connection.prepareStatement("INSERT INTO plumb_probe_vectors (id, embedding) VALUES (?, ?::vector)")) {
            insert.setInt(1, 1);
            insert.setString(2, first);
            insert.addBatch();
            insert.setInt(1, 2);
            insert.setString(2, second);
            insert.addBatch();
            insert.executeBatch();
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id FROM plumb_probe_vectors ORDER BY embedding <-> ?::vector LIMIT 1")) {
            query.setString(1, first);
            try (ResultSet resultSet = query.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : -1;
            }
        }
    }

    private String unitVector(int dimensions, int axis) {
        StringJoiner vector = new StringJoiner(",", "[", "]");
        for (int index = 0; index < dimensions; index++) {
            vector.add(index == axis ? "1" : "0");
        }
        return vector.toString();
    }
}
