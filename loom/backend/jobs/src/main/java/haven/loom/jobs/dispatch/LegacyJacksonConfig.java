package haven.loom.jobs.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The bridge between Spring Boot 4 and the events-api client.
 *
 * Boot 4 moved to Jackson 3 and auto-configures a
 * {@code tools.jackson.databind.ObjectMapper}. The client is built against
 * Jackson 2 and asks for {@code com.fasterxml.jackson.databind.ObjectMapper}, so
 * without this bean its constructor cannot be satisfied and the context fails to
 * start with a message that looks like a missing dependency rather than a
 * version boundary.
 *
 * Jackson 2 is still on the classpath — the client brings it transitively and
 * Boot's BOM still manages its version — so the whole fix is to declare the bean
 * it is looking for. The two ObjectMappers are different types in different
 * packages and do not collide: Spring's own HTTP conversion keeps using the
 * Jackson 3 one, and this serves only the client.
 *
 * JavaTimeModule is not optional: EventDto carries Instant fields.
 *
 * This was established by spike L0-B against a live broker — see
 * loom/L0-FINDINGS.md.
 */
@Configuration
@ConditionalOnProperty(prefix = "dispatch", name = "enabled", havingValue = "true")
public class LegacyJacksonConfig {

    @Bean
    ObjectMapper eventsClientObjectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }
}
