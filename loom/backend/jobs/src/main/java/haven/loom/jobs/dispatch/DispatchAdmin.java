package haven.loom.jobs.dispatch;

import co.altir.events.api.common.client.EventsClient;
import co.altir.events.api.common.dto.EventDto;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request/response over the events bus.
 *
 * Everything in fn-notifications — creating a rule, creating a template — is
 * done by publishing an event to `tenant-input-topic` and waiting for the reply
 * on `tenant-output-topic`. There is no REST for any of it: the operator CRUD
 * controllers are commented out in that service, so the bus is the only door.
 * See loom/L0-FINDINGS.md.
 *
 * The correlation is `requestId`, which the reply echoes back alongside a
 * `statusCodeValue`. One subscription serves every in-flight request.
 */
class DispatchAdmin {

    private static final Logger log = LoggerFactory.getLogger(DispatchAdmin.class);

    static final String INPUT_TOPIC = "tenant-input-topic";
    static final String OUTPUT_TOPIC = "tenant-output-topic";
    static final String COMMON_TOPIC = "notification-input-topic";
    /** The external type the tenant's seeded CREATE_RULE row matches on. */
    static final String CREATE_RULE_EVENT = "my-rule-to-create-rule";

    private final EventsClient client;
    private final Duration replyTimeout;
    private final Map<String, CompletableFuture<Map<String, Object>>> awaiting = new ConcurrentHashMap<>();

    DispatchAdmin(EventsClient client, Duration replyTimeout) {
        this.client = client;
        this.replyTimeout = replyTimeout;
        // One consumer group for the whole process. Two loom workers would
        // compete for the same replies and one would time out waiting for an
        // answer the other consumed — loom runs a single replica, and this is
        // the reason it has to.
        client.subscribeToTopic(OUTPUT_TOPIC, "loom-admin", this::onReply);
    }

    private void onReply(EventDto event) {
        Map<String, Object> context = event.getContext();
        if (context == null) {
            return;
        }
        CompletableFuture<Map<String, Object>> waiter = awaiting.get(String.valueOf(context.get("requestId")));
        if (waiter != null) {
            waiter.complete(context);
        }
    }

    /**
     * Publish a request and wait for its reply.
     *
     * Empty means no reply arrived in time — which is not the same as a refusal
     * and must not be reported as one. `source` is deliberately never set: in
     * default-tenant mode the service overwrites it at every consume funnel.
     */
    Optional<Map<String, Object>> request(String eventType, Map<String, Object> context) {
        String requestId = String.valueOf(context.get("requestId"));
        CompletableFuture<Map<String, Object>> waiter = new CompletableFuture<>();
        awaiting.put(requestId, waiter);
        try {
            client.publish(
                    INPUT_TOPIC,
                    EventDto.builder()
                            .type(eventType)
                            .timestamp(System.currentTimeMillis())
                            .context(context)
                            .build());
            return Optional.of(waiter.get(replyTimeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception noReply) {
            log.warn("No reply to {} ({}) within {}", eventType, requestId, replyTimeout);
            return Optional.empty();
        } finally {
            awaiting.remove(requestId);
        }
    }

    /**
     * Publish a request and wait, retrying a few times before giving up.
     *
     * For requests that depend on a rule created moments earlier. A rule is
     * persisted and acknowledged BEFORE it is live in the rule engine, so an
     * event published immediately after matches nothing and simply goes
     * unanswered — no error, just silence. Verified against a live stack: the
     * first registration on a fresh tenant failed this way and the second, with
     * the rule already in place, succeeded instantly.
     */
    Optional<Map<String, Object>> requestWhenRuleIsLive(String eventType, Map<String, Object> context, int attempts) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Optional<Map<String, Object>> reply = request(eventType, context);
            if (reply.isPresent()) {
                return reply;
            }
            if (attempt < attempts) {
                log.info("No answer to {} yet (attempt {}/{}) — the rule may not be live", eventType, attempt, attempts);
                try {
                    Thread.sleep(Duration.ofSeconds(5));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /** Fire and forget — the notification itself, which answers on its own path. */
    void publish(String eventType, Map<String, Object> context) {
        client.publish(
                INPUT_TOPIC,
                EventDto.builder()
                        .type(eventType)
                        .timestamp(System.currentTimeMillis())
                        .context(context)
                        .build());
    }

    /**
     * A rule definition, in the shape the CREATE_RULE handler expects.
     *
     * `transformations` is an Esper projection: each value is either a
     * `context['x']` extraction from the inbound event or a quoted literal. The
     * quoting is load-bearing — a template id has to arrive as `'uuid'`, inner
     * quotes included, or Esper reads it as a field name.
     */
    static Map<String, Object> rule(
            String requestId,
            String id,
            String eventName,
            String type,
            String subtype,
            String condition,
            Map<String, String> transformations) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("requestId", requestId);
        rule.put("id", id);
        rule.put("eventName", eventName);
        rule.put("correspondingEventType", type);
        rule.put("correspondingEventSubtype", subtype);
        rule.put("condition", condition);
        rule.put("transformations", transformations);
        rule.put("topicToRerouteName", COMMON_TOPIC);
        return rule;
    }

    /** Esper literal: the inner quotes are part of the value. */
    static String literal(String value) {
        return "'" + value + "'";
    }

    /** Esper extraction from the inbound event's context. */
    static String fromContext(String field) {
        return "context['" + field + "']";
    }

    static boolean accepted(Map<String, Object> reply) {
        Object status = reply.get("statusCodeValue");
        if (!(status instanceof Number code)) {
            return false;
        }
        // 409 means it is already there, which is exactly what a second boot
        // should produce and is not a failure.
        return code.intValue() == 200 || code.intValue() == 201 || code.intValue() == 409;
    }
}
