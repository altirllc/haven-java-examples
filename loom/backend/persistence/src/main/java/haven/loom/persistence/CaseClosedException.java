package haven.loom.persistence;

/**
 * The case already reached a terminal state — a closed record refuses edits
 * from every surface (REST and MCP alike), enforced in the service so the
 * adapters cannot disagree. Rendered as HTTP 409.
 */
public class CaseClosedException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public CaseClosedException() {
        super("Case is closed and can no longer be edited");
    }
}
