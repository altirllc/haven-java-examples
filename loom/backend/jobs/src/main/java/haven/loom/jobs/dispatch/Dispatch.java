package haven.loom.jobs.dispatch;

import co.altir.events.api.common.client.EventsClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * loom's notification surface: register its own vocabulary once, then send.
 *
 * loom names the event and supplies the data. It does not name a channel, a
 * template body at send time, or an address — the tenant's rules decide how a
 * notification is rendered and who receives it. That is the same division of
 * labour the Java scaffold used for Signal, and it is why loom holds no
 * notification secret.
 *
 * Off unless `dispatch.enabled` is true. A tenant without Dispatch is not
 * broken; loom records that it could not notify and carries on.
 */
@Component
@ConditionalOnProperty(prefix = "dispatch", name = "enabled", havingValue = "true")
@Import(co.altir.events.api.client.spring.config.EventsClientConfig.class)
public class Dispatch {

    private static final Logger log = LoggerFactory.getLogger(Dispatch.class);
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(20);

    private final DispatchProperties properties;
    private final DispatchAdmin admin;
    private final AtomicReference<String> notReady = new AtomicReference<>("not registered yet");

    public Dispatch(EventsClient client, DispatchProperties properties) {
        this.properties = properties;
        this.admin = new DispatchAdmin(client, REPLY_TIMEOUT);
    }

    /** Null when loom can send; otherwise why it cannot. */
    public String unavailable() {
        String missing = properties.missing();
        return missing != null ? missing : notReady.get();
    }

    /**
     * Register loom's rules and template, in the background, best-effort.
     *
     * Background because Dispatch is a neighbour: a tenant whose events stack is
     * slow to start must not stop loom booting. Best-effort because the same is
     * true of one that never starts at all — the notifier reports itself
     * unavailable and the case timeline says so.
     *
     * Idempotent: a second boot gets 409s, which count as success.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (properties.missing() != null) {
            log.info("Dispatch not configured ({}) — loom will not notify", properties.missing());
            return;
        }
        Thread.ofVirtual().name("loom-dispatch-register").start(this::registerNow);
    }

    private void registerNow() {
        try {
            // 1. Teach the tenant to accept a template from loom. Needs the
            //    seeded CREATE_RULE row to already exist — that is the one step
            //    loom cannot do for itself.
            if (!ensureRule(
                    properties.templateRuleId(),
                    "loom-create-template",
                    "REQUEST",
                    "CREATE_TEMPLATE",
                    "true",
                    fields("requestId", "id", "tenantId", "categoryId", "subcategoryId", "name",
                            "notificationType", "format", "body"))) {
                return;
            }

            // 2. loom's own template. Its variables are the ones sendData supplies.
            if (!ensureTemplate()) {
                return;
            }

            // 3. The rule that turns loom's domain event into a notification.
            if (!ensureRule(
                    properties.notifyRuleId(),
                    properties.eventName(),
                    "NOTIFICATION",
                    "SEND_AND_FORGET_NOTIFICATION",
                    "context('categoryId') = " + DispatchAdmin.literal(properties.categoryId())
                            + " and context('subcategoryId') = "
                            + DispatchAdmin.literal(properties.subcategoryId()),
                    notifyTransformations())) {
                return;
            }

            notReady.set(null);
            log.info("Dispatch ready — loom can notify through event '{}'", properties.eventName());
        } catch (RuntimeException failed) {
            notReady.set("registration failed: " + failed.getMessage());
            log.warn("Could not register loom's notification rules", failed);
        }
    }

    private boolean ensureRule(
            String id,
            String eventName,
            String type,
            String subtype,
            String condition,
            Map<String, String> transformations) {
        Optional<Map<String, Object>> reply = admin.request(
                DispatchAdmin.CREATE_RULE_EVENT,
                DispatchAdmin.rule(
                        "loom-rule-" + id, id, eventName, type, subtype, condition, transformations));
        return record(reply, "rule " + eventName);
    }

    private boolean ensureTemplate() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("requestId", "loom-template-" + properties.templateId());
        template.put("id", properties.templateId());
        template.put("tenantId", "default");
        template.put("categoryId", properties.categoryId());
        template.put("subcategoryId", properties.subcategoryId());
        template.put("name", "loom-case-needs-a-human");
        template.put("notificationType", "EMAIL");
        template.put("format", "HTML");
        template.put("body", TEMPLATE_BODY);
        // Retried: this is the request that depends on the rule created a
        // moment ago, and a rule is acknowledged before it is live.
        return record(admin.requestWhenRuleIsLive("loom-create-template", template, 4), "template");
    }

    private boolean record(Optional<Map<String, Object>> reply, String what) {
        if (reply.isEmpty()) {
            notReady.set("no reply from Dispatch when registering the " + what);
            log.warn("Dispatch did not answer when registering the {}", what);
            return false;
        }
        if (!DispatchAdmin.accepted(reply.get())) {
            notReady.set("Dispatch refused the " + what + ": " + reply.get().get("statusCodeValue"));
            log.warn("Dispatch refused the {}: {}", what, reply.get());
            return false;
        }
        return true;
    }

    /**
     * The rule's projection. `notificationType` is a literal because the RULE
     * decides the channel, not the sender; `template` is a quoted literal id for
     * the same reason. Everything else is lifted off the event loom publishes.
     */
    private Map<String, String> notifyTransformations() {
        Map<String, String> transformations = new LinkedHashMap<>();
        // APP_PUSH publishes the rendered notification to a topic instead of
        // calling a vendor, so this path needs no SES / Twilio / Firebase
        // credentials to work end to end.
        transformations.put("notificationType", "APP_PUSH");
        transformations.put("eventPriority", DispatchAdmin.fromContext("eventPriority"));
        transformations.put("categoryId", DispatchAdmin.fromContext("categoryId"));
        transformations.put("subcategoryId", DispatchAdmin.fromContext("subcategoryId"));
        transformations.put("targets", DispatchAdmin.fromContext("targets"));
        transformations.put("textBody", DispatchAdmin.fromContext("textBody"));
        transformations.put("title", DispatchAdmin.fromContext("title"));
        transformations.put("templateData", DispatchAdmin.fromContext("templateData"));
        transformations.put("scheduleAt", DispatchAdmin.fromContext("scheduleAt"));
        transformations.put("template", DispatchAdmin.literal(properties.templateId()));
        return transformations;
    }

    private static final String TEMPLATE_BODY =
            "<p><b>${title}</b></p><p>Case ${caseId} (Anvil item ${anvilItemId}) needs a person.</p>"
                    + "<p>Why: ${reason}</p><p>loom said: ${rationale}</p>";

    /** What loom sends. The tenant's rule decides the channel and the audience. */
    public record Notification(
            UUID caseId, String anvilItemId, String title, String reason, String rationale) {}

    /**
     * Send one notification.
     *
     * Fire and forget by design: fn-notifications accepts and delivers
     * asynchronously, so waiting here would buy a slower workflow and no more
     * certainty. What the caller gets back is whether loom managed to PUBLISH,
     * which is the only part loom can honestly attest to.
     */
    public void send(Notification notification) {
        String blocked = unavailable();
        if (blocked != null) {
            throw new IllegalStateException("Dispatch unavailable: " + blocked);
        }

        Map<String, Object> templateData = new LinkedHashMap<>();
        templateData.put("title", notification.title());
        templateData.put("caseId", notification.caseId().toString());
        templateData.put("anvilItemId", notification.anvilItemId());
        templateData.put("reason", notification.reason());
        templateData.put("rationale", notification.rationale() == null ? "" : notification.rationale());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("requestId", "loom-notify-" + notification.caseId());
        event.put("eventPriority", "REGULAR");
        event.put("categoryId", properties.categoryId());
        event.put("subcategoryId", properties.subcategoryId());
        event.put("targets", List.copyOf(properties.recipients()));
        event.put("title", notification.title());
        event.put("textBody", "");
        event.put("templateData", templateData);

        admin.publish(properties.eventName(), event);
        log.info("Notified {} recipient(s) about case {}", properties.recipients().size(), notification.caseId());
    }

    private static Map<String, String> fields(String... names) {
        Map<String, String> transformations = new LinkedHashMap<>();
        for (String name : names) {
            transformations.put(name, DispatchAdmin.fromContext(name));
        }
        return transformations;
    }
}
