package haven.loom.persistence;

import org.postgresql.util.PSQLException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns an unreachable-database boot failure into Boot's clean
 * APPLICATION FAILED TO START block instead of a bean-creation stack trace.
 * Registered in META-INF/spring.factories.
 */
public class PostgresUnreachableFailureAnalyzer extends AbstractFailureAnalyzer<PSQLException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, PSQLException cause) {
        // Only connection failures (SQLSTATE class 08). Anything else — a
        // migration conflict, a constraint violation — must surface as itself,
        // not masquerade as an unreachable database.
        String state = cause.getSQLState();
        if (state == null || !state.startsWith("08")) {
            return null;
        }
        return new FailureAnalysis(
                "PostgreSQL is unreachable:\n\n" + cause.getMessage(),
                "Start the local stack with `docker compose up -d` — the app already waits ~30s for the "
                        + "database, so if this fired the stack is down, not merely starting. In a cell, check "
                        + "the postgres service.",
                cause);
    }
}
