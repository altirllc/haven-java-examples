package haven.loom.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import haven.loom.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * In production, missing edge headers must yield ANONYMOUS — never a fabricated
 * user, which would mask an auth outage and forge the audit actor.
 */
class IdentityResolverTest {

    private static IdentityResolver resolver(String... profiles) {
        return resolver(Role.MEMBER, profiles);
    }

    private static IdentityResolver resolver(Role devRole, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new IdentityResolver(environment, new HavenProperties("acme", "dev", devRole));
    }

    private static MockHttpServletRequest request(String sub, String email, String roles) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (sub != null) {
            request.addHeader("X-Auth-Request-User", sub);
        }
        if (email != null) {
            request.addHeader("X-Auth-Request-Email", email);
        }
        if (roles != null) {
            request.addHeader("X-Auth-Request-Roles", roles);
        }
        return request;
    }

    @Test
    void productionNeverFabricatesAUser() {
        RequestUser user = resolver("production").resolve(request(null, null, null));

        assertThat(user.authenticated()).isFalse();
        assertThat(user.sub()).isEmpty();
        assertThat(user.meets(Role.VIEWER))
                .as("even the viewer floor requires an authenticated caller")
                .isFalse();
    }

    @Test
    void requireDeniesTheAnonymousProductionCallerWith403() {
        CurrentUser currentUser = new CurrentUser(resolver("production"));

        assertThatThrownBy(() -> currentUser.require(request(null, null, null), Role.VIEWER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void localDevFallsBackToTheConfiguredDevIdentity() {
        RequestUser user = resolver().resolve(request(null, null, null));

        assertThat(user.sub()).isEqualTo("local-dev");
        assertThat(user.email()).isEqualTo("dev@acme");
        assertThat(user.meets(Role.MEMBER)).isTrue();
        assertThat(user.meets(Role.ADMIN)).as("member is the default, not admin").isFalse();
    }

    @Test
    void devRoleSelectsTheLocalTier() {
        RequestUser viewer = resolver(Role.VIEWER).resolve(request(null, null, null));
        assertThat(viewer.meets(Role.VIEWER)).isTrue();
        assertThat(viewer.meets(Role.MEMBER)).isFalse();

        RequestUser admin = resolver(Role.ADMIN).resolve(request(null, null, null));
        assertThat(admin.meets(Role.ADMIN)).isTrue();
    }

    @Test
    void edgeHeadersOutrankTheDevRole() {
        RequestUser user = resolver(Role.ADMIN).resolve(request("zitadel-sub-1", "grace@acme.co", "viewer"));

        assertThat(user.email()).isEqualTo("grace@acme.co");
        assertThat(user.meets(Role.MEMBER)).as("the edge's roles win over haven.role").isFalse();
    }

    @Test
    void edgeHeadersResolveTheRealUserInAnyProfile() {
        RequestUser user = resolver("production")
                .resolve(request("zitadel-sub-1", "grace@acme.co", "viewer, member"));

        assertThat(user.authenticated()).isTrue();
        assertThat(user.sub()).isEqualTo("zitadel-sub-1");
        assertThat(user.email()).isEqualTo("grace@acme.co");
        assertThat(user.roles()).containsExactly("viewer", "member");
        assertThat(user.meets(Role.MEMBER)).isTrue();
        assertThat(user.meets(Role.ADMIN)).isFalse();
    }

    @Test
    void subAndEmailBackfillEachOther() {
        RequestUser emailOnly = resolver("production").resolve(request(null, "grace@acme.co", null));
        assertThat(emailOnly.sub()).isEqualTo("grace@acme.co");

        RequestUser subOnly = resolver("production").resolve(request("zitadel-sub-1", null, null));
        assertThat(subOnly.email()).isEqualTo("zitadel-sub-1");
    }

    @Test
    void unknownGrantsFloorToViewerButNoHigher() {
        RequestUser user = resolver("production").resolve(request("zitadel-sub-1", null, "billing-admin"));

        assertThat(user.meets(Role.VIEWER)).as("authenticated users always hold the viewer floor").isTrue();
        assertThat(user.meets(Role.MEMBER)).isFalse();
    }
}
