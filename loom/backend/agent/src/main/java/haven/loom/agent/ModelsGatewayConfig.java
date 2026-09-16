package haven.loom.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Startup probe of the platform model gateway.
 *
 * Auth is just the tenant's virtual key on spring.ai.openai.api-key — no
 * credential wiring. This probe keeps the backing-service contract: the app
 * already refuses to boot without Postgres or Temporal; the model provider is
 * a backing service like any other. GET /v1/models authenticates the key and
 * proves the gateway is reachable, so failures surface at boot, not first call.
 */
@Configuration
@EnableConfigurationProperties(ModelsGatewayProperties.class)
public class ModelsGatewayConfig {

    static final class ModelsGatewayProbe {}

    @Bean
    ModelsGatewayProbe modelsGatewayProbe(ModelsGatewayProperties gateway) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(gateway.endpoint() + "/v1/models"))
                .header("Authorization", "Bearer " + gateway.apiKey())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw new ModelsGatewayUnavailableException(
                        "the model gateway returned HTTP " + response.statusCode() + " for GET /v1/models", null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ModelsGatewayUnavailableException(interrupted.getMessage(), interrupted);
        } catch (IOException failed) {
            throw new ModelsGatewayUnavailableException(failed.getMessage(), failed);
        }
        return new ModelsGatewayProbe();
    }
}
