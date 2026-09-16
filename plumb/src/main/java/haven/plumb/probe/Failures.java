package haven.plumb.probe;

import java.util.ArrayList;
import java.util.List;

/** Renders a throwable as one readable line. */
public final class Failures {

    private static final int MAX_CAUSES = 5;

    private Failures() {}

    /**
     * The whole cause chain, joined by arrows.
     *
     * The chain is the point: seam failures are almost always reported by a
     * wrapper whose own message is useless ("Could not open connection"), while
     * the cause two levels down is the actual answer ("password authentication
     * failed for user"). Printing only the top exception hides the diagnosis,
     * which is the entire product here.
     */
    public static String describe(Throwable failure) {
        List<String> chain = new ArrayList<>();
        Throwable current = failure;
        while (current != null && chain.size() < MAX_CAUSES) {
            String message = current.getMessage();
            chain.add(message == null || message.isBlank()
                    ? current.getClass().getSimpleName()
                    : current.getClass().getSimpleName() + ": " + message.strip());
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return String.join(" <- ", chain);
    }
}
