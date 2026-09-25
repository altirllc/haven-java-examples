package haven.plumb.probe.impl;

import haven.plumb.config.Neo4jProperties;
import haven.plumb.config.Values;
import haven.plumb.probe.Probe;
import haven.plumb.probe.ProbeGroup;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Logging;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.TransactionConfig;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The tenant's Neo4j: connect with the tenant credential, then write and read
 * back a node inside a transaction that is never committed.
 *
 * Verifying connectivity alone would pass against a Neo4j the app cannot write
 * to, which is the failure that actually happens — Neo4j ships with a default
 * credential that the tenant secret is supposed to replace, and a password that
 * was never rotated fails on the first write rather than at connect. So the
 * write is the check.
 *
 * But the probe runs every 60s against a database the tenant owns and keeps, so
 * it must leave nothing. Rollback rather than delete: cleanup code has to run to
 * work, and a cancelled sweep or a dropped connection skips it, whereas an
 * uncommitted transaction is discarded by the server whatever happens to us.
 *
 * The node carries no label and no properties either. Neo4j creates label and
 * property-key tokens eagerly and KEEPS them through a rollback — verified on a
 * live tenant — so naming anything here would permanently add a name to the
 * tenant's token store. Identifying the node by elementId inside the same
 * transaction needs neither, and leaves the database byte-identical.
 */
@Component
@Order(35)
public class Neo4jProbe implements Probe {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(5);

    private final Neo4jProperties properties;

    public Neo4jProbe(Neo4jProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return "neo4j";
    }

    @Override
    public ProbeGroup group() {
        return ProbeGroup.DATA;
    }

    @Override
    public String title() {
        return "Neo4j";
    }

    @Override
    public String proves() {
        return "connect with the tenant credential and write a node the grant allows";
    }

    @Override
    public Outcome run() {
        if (!properties.configured()) {
            return Outcome.skipped("NEO4J_URI / NEO4J_USERNAME / NEO4J_PASSWORD not set — from tenant-neo4j-secret");
        }

        // Short timeouts for the same reason as Mongo: the driver's defaults
        // outlive the sweep deadline and turn "connection refused" into
        // "timed out", which names the wrong thing.
        Config config = Config.builder()
                .withConnectionTimeout(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .withLogging(Logging.none())
                .build();

        // withMaxTransactionRetryTime is deliberately absent: it only bounds
        // MANAGED transactions (executeRead/executeWrite), and this probe runs an
        // explicit one. The bound that actually applies is the per-transaction
        // timeout below — without it a Neo4j that accepts the connection but
        // blocks the write (store full, read-only, lock contention) would hold
        // the probe in a socket read until the sweep deadline, report a generic
        // "timed out" instead of naming the fault, and leave the transaction
        // open server-side because cancellation is best-effort.
        TransactionConfig txConfig =
                TransactionConfig.builder().withTimeout(WRITE_TIMEOUT).build();

        // A driver per sweep, closed after: a cached pool would keep reporting
        // success on a credential that has since been rotated out from under it.
        try (Driver driver = GraphDatabase.driver(
                properties.uri(), AuthTokens.basic(properties.username(), properties.password()), config)) {

            long verifyStart = System.nanoTime();
            driver.verifyConnectivity();
            long verifyMillis = Duration.ofNanos(System.nanoTime() - verifyStart).toMillis();

            SessionConfig sessionConfig = Values.isSet(properties.database())
                    ? SessionConfig.forDatabase(properties.database())
                    : SessionConfig.defaultConfig();

            try (Session session = driver.session(sessionConfig);
                    Transaction tx = session.beginTransaction(txConfig)) {
                // CREATE alone is the check: it fails on a credential that can
                // read but not write, which is the failure a connect check sails
                // past. Reading the node back afterwards would add nothing —
                // inside its own uncommitted transaction it is trivially there —
                // and an unbound MATCH risks an AllNodesScan of a graph the
                // tenant owns and fills, once a minute, forever.
                tx.run("CREATE (n)").consume();
                // Never committed. try-with-resources also rolls back an open
                // transaction on the way out, so an exception between here and
                // now cannot leave one dangling either.
                tx.rollback();
            }

            return Outcome.ok(
                    "round trip verified on " + properties.uri(),
                    "connect: " + verifyMillis + "ms",
                    "database: " + (Values.isSet(properties.database()) ? properties.database() : "(default)"),
                    "write proven and rolled back — nothing committed");
        }
    }
}
