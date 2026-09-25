package haven.plumb.probe.impl;

import haven.plumb.config.Values;
import haven.plumb.config.ValkeyProperties;
import haven.plumb.probe.Probe;
import haven.plumb.probe.ProbeGroup;
import java.time.Duration;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;

/**
 * The tenant's Valkey: ping, then set and read back a key.
 *
 * A ping proves the port answers, which on a cache is nearly the whole story —
 * there is no credential and no grant to get wrong. What it does not prove is
 * that the instance will accept a write, and a cache that has hit its memory
 * limit with an eviction policy of noeviction answers ping and refuses SET.
 * That is the failure worth catching, so the round trip is the check.
 *
 * The key is written with a short TTL as well as being deleted, so an aborted
 * sweep cannot leave anything behind in a tenant's cache.
 */
@Component
@Order(37)
public class ValkeyProbe implements Probe {

    private static final int TIMEOUT_MILLIS = 5_000;
    private static final int KEY_TTL_SECONDS = 60;

    private final ValkeyProperties properties;

    public ValkeyProbe(ValkeyProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return "valkey";
    }

    @Override
    public ProbeGroup group() {
        return ProbeGroup.DATA;
    }

    @Override
    public String title() {
        return "Valkey";
    }

    @Override
    public String proves() {
        return "the cache answers and accepts a write that reads back";
    }

    @Override
    public Outcome run() {
        // A port that was supplied but is not a port is a FAILURE, not a skip:
        // skipped rows count as healthy, so folding the two together would show
        // an all-green page while the cache was never probed at all.
        if (Values.isSet(properties.port()) && properties.portOrZero() == 0) {
            return Outcome.fail("REDIS_PORT is not a port: " + properties.port());
        }
        if (!properties.configured()) {
            return Outcome.skipped("REDIS_HOST / REDIS_PORT not set — injected by the bundle");
        }

        // A connection per sweep, closed after — same reasoning as Mongo: this
        // is a cold path, and a cached connection reports on a cache that may
        // have been restarted underneath it.
        try (Jedis jedis = new Jedis(properties.host(), properties.portOrZero(), TIMEOUT_MILLIS, TIMEOUT_MILLIS)) {
            long pingStart = System.nanoTime();
            String pong = jedis.ping();
            long pingMillis = Duration.ofNanos(System.nanoTime() - pingStart).toMillis();

            String token = UUID.randomUUID().toString();
            String key = properties.keyPrefix() + token;
            jedis.setex(key, KEY_TTL_SECONDS, "plumb round trip");
            String found = jedis.get(key);
            jedis.del(key);

            if (found == null) {
                return Outcome.fail("key " + key + " was written but did not read back");
            }
            return Outcome.ok(
                    properties.host() + ":" + properties.portOrZero() + ", round trip verified",
                    "ping: " + pong + " in " + pingMillis + "ms",
                    "key prefix: " + properties.keyPrefix(),
                    "key: " + token);
        }
    }
}
