package haven.plumb.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * Where the page lives as the browser sees it, always ending in a slash.
 *
 * In a cell the edge strips {@code /plumb} before the request arrives, so the app
 * sees {@code /} and has no idea it is not at the root. A redirect to {@code /}
 * then lands on the tenant's root, and a relative form action breaks as soon as
 * someone opens {@code /plumb} without the trailing slash — both are a 404 for the
 * person pressing "Run all". Traefik's strip middleware records what it removed
 * in {@code X-Forwarded-Prefix}; that is the only source for it.
 *
 * The header ends up in a {@code Location} and in the page's markup, so it is
 * accepted only as plain path segments. Anything else — {@code //host}, a scheme,
 * quotes, dot segments — is treated as absent rather than escaped: a prefix that
 * needs escaping is not one Traefik wrote.
 */
final class BasePath {

    static final String HEADER = "X-Forwarded-Prefix";

    private static final Pattern SEGMENTS = Pattern.compile("(/[A-Za-z0-9_-]+)+");

    private BasePath() {}

    static String of(HttpServletRequest request) {
        String prefix = request.getHeader(HEADER);
        if (prefix == null) {
            return "/";
        }
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return SEGMENTS.matcher(prefix).matches() ? prefix + "/" : "/";
    }
}
