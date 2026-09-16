package haven.loom.api;

import static org.assertj.core.api.Assertions.assertThat;

import haven.loom.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * haven.role must reach the Role enum, and anything it cannot reach must fail
 * the context at boot. Spring's lenient converter maps a blank enum value to
 * null rather than throwing, so only @NotNull turns that into a startup failure
 * instead of a null role and an NPE on the first header-less request.
 *
 * Binds through a real context: Binder alone applies no JSR-380 validation, so
 * it would pass every case below.
 */
class HavenPropertiesTest {

    @EnableConfigurationProperties(HavenProperties.class)
    static class Config {
    }

    private static ApplicationContextRunner haven(String user, String role) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .withPropertyValues("haven.tenant-id=acme", "haven.user=" + user, "haven.role=" + role);
    }

    private static HavenProperties bound(org.springframework.context.ApplicationContext context) {
        return context.getBean(HavenProperties.class);
    }

    @Test
    void bindsEachWireRole() {
        haven("dev", "viewer").run(c -> assertThat(bound(c).role()).isEqualTo(Role.VIEWER));
        haven("dev", "member").run(c -> assertThat(bound(c).role()).isEqualTo(Role.MEMBER));
        haven("dev", "admin").run(c -> assertThat(bound(c).role()).isEqualTo(Role.ADMIN));
    }

    @Test
    void bindsTheTenantAndUser() {
        haven("tess", "member").run(c -> {
            assertThat(bound(c).tenantId()).isEqualTo("acme");
            assertThat(bound(c).user()).isEqualTo("tess");
        });
    }

    @Test
    void refusesToStartOnAnUnknownRole() {
        haven("dev", "viewr")
                .run(c -> assertThat(c).getFailure().hasStackTraceContaining("No enum constant haven.loom.domain.Role.viewr"));
    }

    @Test
    void refusesToStartOnAnEmptyRole() {
        haven("dev", "").run(c -> assertThat(c).getFailure().hasStackTraceContaining("on field 'role'"));
    }

    @Test
    void refusesToStartOnABlankUser() {
        haven("", "member").run(c -> assertThat(c).getFailure().hasStackTraceContaining("on field 'user'"));
    }
}
