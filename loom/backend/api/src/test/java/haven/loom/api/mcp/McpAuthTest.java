package haven.loom.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import haven.loom.domain.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The edge leaves /mcp ungated, so every rule here is enforced in-process or
 * not at all: role gating, the org/audience validators, the RFC 9728 pointer.
 */
class McpAuthTest {

    private static final String ROLES_CLAIM = "urn:zitadel:iam:org:project:roles";
    private static final String ORG_CLAIM = "urn:zitadel:iam:user:resourceowner:name";

    private final McpCaller caller = new McpCaller();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("zitadel-sub-1");
    }

    private static void authenticateAs(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void unauthenticatedCallsAreRejectedOutright() {
        assertThatThrownBy(() -> caller.require(Role.VIEWER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unauthenticated");
    }

    @Test
    void aViewerIsRefusedWrites() {
        authenticateAs(jwt().claim(ROLES_CLAIM, Map.of("viewer", Map.of())).build());

        assertThatThrownBy(() -> caller.require(Role.MEMBER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("member");
    }

    @Test
    void aMemberMayWriteAndAnyAuthenticatedUserMayRead() {
        authenticateAs(jwt().claim(ROLES_CLAIM, Map.of("member", Map.of()))
                .claim("email", "grace@acme.co")
                .build());

        assertThat(caller.require(Role.MEMBER)).isEqualTo("grace@acme.co");
        assertThat(caller.require(Role.VIEWER)).isEqualTo("grace@acme.co");
    }

    @Test
    void actorFallsBackFromEmailToUsernameToSubject() {
        authenticateAs(jwt().claim(ROLES_CLAIM, Map.of("member", Map.of()))
                .claim("preferred_username", "grace")
                .build());
        assertThat(caller.require(Role.MEMBER)).isEqualTo("grace");

        authenticateAs(jwt().claim(ROLES_CLAIM, Map.of("member", Map.of())).build());
        assertThat(caller.require(Role.MEMBER)).isEqualTo("zitadel-sub-1");
    }

    @Test
    void aValidTokenForAnotherTenantIsNotValidHere() {
        var validator = McpSecurityConfig.orgIs("acme");

        assertThat(validator.validate(jwt().claim(ORG_CLAIM, "acme").build()).hasErrors()).isFalse();
        assertThat(validator.validate(jwt().claim(ORG_CLAIM, "globex").build()).hasErrors()).isTrue();
        assertThat(validator.validate(jwt().claim("unrelated", "x").build()).hasErrors())
                .as("a token with no org claim must fail, not pass by default")
                .isTrue();
    }

    @Test
    void theAudienceMustContainTheTenantMcpClient() {
        var validator = McpSecurityConfig.audienceContains("mcp-client-id");

        assertThat(validator.validate(jwt().audience(List.of("other", "mcp-client-id")).build()).hasErrors())
                .isFalse();
        assertThat(validator.validate(jwt().audience(List.of("other")).build()).hasErrors()).isTrue();
        assertThat(validator.validate(jwt().claim("unrelated", "x").build()).hasErrors())
                .as("a token with no audience must fail, not pass by default")
                .isTrue();
    }

    @Test
    void blankConfigurationMeansDenyClosed() {
        assertThat(new McpProperties("", "").configured()).isFalse();
        assertThat(new McpProperties("https://identity.acme", "").configured()).isFalse();
        assertThat(new McpProperties("", "mcp-client-id").configured()).isFalse();
        assertThat(new McpProperties("https://identity.acme", "mcp-client-id").configured()).isTrue();
    }

    @Test
    void the401AdvertisesTheCanonicalResourceMetadata() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "acme.haven.karrma.org");
        request.addHeader("X-Forwarded-Prefix", "/mcp/loom");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new McpSecurityConfig.McpAuthenticationEntryPoint()
                .commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate"))
                .isEqualTo("Bearer resource_metadata=\"https://acme.haven.karrma.org"
                        + "/.well-known/oauth-protected-resource/mcp/loom\"");
    }
}
