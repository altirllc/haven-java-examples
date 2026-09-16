package haven.plumb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * plumb — the Haven connectivity probe.
 *
 * One container that performs a real read-write round trip against every seam a
 * Haven application is wired to, and reports each one green or red. Run it first
 * in a new cell or tenant, and point it at a tenant whose apps are misbehaving to
 * find out which side of the wire is at fault.
 *
 * THE ONE RULE THIS APP INVERTS: every other Haven service fails fast — a
 * missing database, an unreachable model gateway or a bad Mongo URI kills the
 * process at boot, on purpose. plumb must do the opposite. A diagnostic that
 * refuses to start when something is broken cannot report the breakage, so
 * nothing here is allowed to make a seam's absence fatal. That is why there is
 * no spring-boot-starter-data-* on the classpath, no @NotBlank on any seam
 * property, and why every client is built lazily inside the probe that owns it.
 * See CLAUDE.md before you "fix" any of that.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PlumbApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlumbApplication.class, args);
    }
}
