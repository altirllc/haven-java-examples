package haven.loom.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    private final CurrentUser currentUser;
    private final HavenProperties haven;

    public MeController(CurrentUser currentUser, HavenProperties haven) {
        this.currentUser = currentUser;
        this.haven = haven;
    }

    /** The signed-in tenant user, as resolved from the edge. Drives the header menu. */
    @GetMapping("/api/me")
    public Map<String, Object> me(HttpServletRequest request) {
        RequestUser user = currentUser.of(request);
        Map<String, Object> body = new HashMap<>();
        body.put("sub", user.sub());
        body.put("email", user.email());
        body.put("roles", user.roles());
        body.put("tenant", haven.tenantId());
        // `features` is where a capability the tenant may not have configured is
        // advertised. The notifier that will populate it — escalation through
        // Dispatch — is not wired yet, so it reports nothing rather than
        // reporting a capability that does not exist.
        body.put("features", Map.of());
        return body;
    }
}
