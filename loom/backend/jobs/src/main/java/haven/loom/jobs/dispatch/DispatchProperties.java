package haven.loom.jobs.dispatch;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where loom sends a notification, and what the tenant must already have set up
 * for it to land.
 *
 * The division of labour matters and is deliberate:
 *
 *  - **loom owns its own vocabulary.** The event type, the routing rule that
 *    turns it into a notification, and the template body are loom's — nobody
 *    else knows them, so loom registers them itself at boot.
 *  - **The tenant owns its taxonomy and its people.** The category and
 *    subcategory the notification files under, and the recipients who receive
 *    it, are configured here rather than invented. An app that made up a
 *    recipient would be guessing who deserves to be woken up.
 *  - **The tenant owns the bootstrap.** fn-notifications cannot accept its first
 *    rule without a `CREATE_RULE` row seeded straight into its store — see
 *    loom/L0-FINDINGS.md. That is a provisioning step, not something loom should
 *    reach into a neighbour's database to do.
 *
 * `enabled` defaults to false. A tenant without Dispatch is not broken; loom
 * records that it could not notify and carries on.
 */
@ConfigurationProperties(prefix = "dispatch")
public record DispatchProperties(
        boolean enabled,
        // The external event type loom publishes. Its routing rule fires on this.
        String eventName,
        // Stable ids. A rule's identity is its ID, not its event name, so
        // re-registering the same id is an update and re-registering a DIFFERENT
        // id leaves the old rule live: the tenant then has two rules matching
        // loom's event and sends two notifications for every case. Observed on a
        // live stack. Change these only when decommissioning the old rule too.
        String notifyRuleId,
        String templateRuleId,
        String templateId,
        // The tenant's taxonomy. Must already exist - loom does not create these.
        String categoryId,
        String subcategoryId,
        // Who hears about it. Must already exist as users with preferences.
        List<String> recipients) {

    public DispatchProperties {
        recipients = recipients == null ? List.of() : recipients;
    }

    /**
     * Whether loom has everything it needs to send. Reported rather than
     * assumed: a half-configured tenant should produce a case timeline that says
     * why nothing was sent, not silence.
     */
    public String missing() {
        if (!enabled) {
            return "dispatch.enabled is false";
        }
        if (isBlank(categoryId) || isBlank(subcategoryId)) {
            return "dispatch.category-id / dispatch.subcategory-id are not set";
        }
        if (recipients.isEmpty()) {
            return "dispatch.recipients is empty — loom will not invent who to notify";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
