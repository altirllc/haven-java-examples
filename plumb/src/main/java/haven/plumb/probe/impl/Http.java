package haven.plumb.probe.impl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The HTTP seams share one client: the model gateway, the identity provider and
 * the sibling apps.
 *
 * The JDK client, not RestClient or WebClient — this is the layer under test, so
 * the fewer abstractions between the probe and the socket the more the result
 * means. It also keeps the failure legible: a connect refusal arrives as
 * ConnectException rather than wrapped three deep in framework types.
 *
 * Redirects are NOT followed. Every endpoint here is expected to answer
 * directly; a redirect means something is in front of the service that should
 * not be (an SSO portal, a captive proxy), and quietly following it would turn
 * that into a green row.
 */
@Component
public class Http {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** An HTTP exchange that completed — the status is data, not an exception. */
    public record Reply(int status, String body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** Enough of the body to diagnose, not enough to wreck the page. */
        public String snippet() {
            String flattened = body.replaceAll("\\s+", " ").strip();
            return flattened.length() <= 200 ? flattened : flattened.substring(0, 200) + "...";
        }
    }

    public Reply get(String url, String... headers) throws IOException, InterruptedException {
        return send(builder(url, headers).GET().build());
    }

    public Reply postJson(String url, String json, String... headers) throws IOException, InterruptedException {
        return send(builder(url, headers)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private HttpRequest.Builder builder(String url, String... headers) {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT);
        for (int index = 0; index + 1 < headers.length; index += 2) {
            request.header(headers[index], headers[index + 1]);
        }
        return request;
    }

    private Reply send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }
}
