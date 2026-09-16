package haven.plumb.probe.impl;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import haven.plumb.config.MongoProperties;
import haven.plumb.probe.Probe;
import haven.plumb.probe.ProbeGroup;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The tenant's MongoDB: ping, then write and read back a document.
 *
 * The ping alone would pass against a Mongo the app has no rights on, which is
 * the failure that actually happens — the URI carries authSource=admin and a
 * credential minted for a different database. Writing proves the grant.
 *
 * Every Haven tenant gets Mongo whether or not its apps use it (the agent
 * scaffold wires it and deliberately stores nothing), so this row is usually the
 * first evidence that the tenant's Mongo was provisioned correctly at all.
 */
@Component
@Order(30)
public class MongoProbe implements Probe {

    private final MongoProperties properties;

    public MongoProbe(MongoProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return "mongodb";
    }

    @Override
    public ProbeGroup group() {
        return ProbeGroup.DATA;
    }

    @Override
    public String title() {
        return "MongoDB";
    }

    @Override
    public String proves() {
        return "connect with the tenant credential and write a document the grant allows";
    }

    @Override
    public Outcome run() {
        if (!properties.configured()) {
            return Outcome.skipped("MONGODB_URI not set — composed by the bundle from tenant-mongodb-secret");
        }

        ConnectionString connectionString = new ConnectionString(properties.uri());
        // Short timeouts on purpose. The driver's defaults spend 30 seconds
        // looking for a server, which outlives the sweep deadline and turns a
        // precise "connection refused" into a vague "timed out".
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .applyToSocketSettings(socket -> socket.connectTimeout(5, TimeUnit.SECONDS))
                .build();

        // A client per sweep, closed after: this is a cold-path check, and a
        // cached pool would stop reporting a credential that was rotated out
        // from under it.
        try (MongoClient client = MongoClients.create(settings)) {
            String databaseName = connectionString.getDatabase() == null ? "test" : connectionString.getDatabase();
            MongoDatabase database = client.getDatabase(databaseName);

            long pingStart = System.nanoTime();
            database.runCommand(new Document("ping", 1));
            long pingMillis = Duration.ofNanos(System.nanoTime() - pingStart).toMillis();

            UUID token = UUID.randomUUID();
            MongoCollection<Document> collection = database.getCollection(properties.collection());
            collection.insertOne(new Document("_id", token.toString()).append("note", "plumb round trip"));
            Document found = collection.find(new Document("_id", token.toString())).first();
            collection.deleteOne(new Document("_id", token.toString()));

            if (found == null) {
                return Outcome.fail("document " + token + " was written but did not read back");
            }
            return Outcome.ok(
                    "database " + databaseName + ", round trip verified",
                    "ping: " + pingMillis + "ms",
                    "collection: " + properties.collection(),
                    "document: " + token);
        }
    }
}
