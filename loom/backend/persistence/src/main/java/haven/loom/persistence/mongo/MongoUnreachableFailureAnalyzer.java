package haven.loom.persistence.mongo;

import com.mongodb.MongoTimeoutException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns an unreachable-MongoDB boot failure (the MongoConnectionCheck ping)
 * into Boot's clean APPLICATION FAILED TO START block instead of a
 * server-selection stack trace. Registered in META-INF/spring.factories.
 */
public class MongoUnreachableFailureAnalyzer extends AbstractFailureAnalyzer<MongoTimeoutException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MongoTimeoutException cause) {
        return new FailureAnalysis(
                "MongoDB is unreachable:\n\n" + cause.getMessage(),
                "Start the local stack with `docker compose up -d` — the driver already waits ~30s for a "
                        + "server, so if this fired the stack is down, not merely starting. In a cell, check "
                        + "the tenant's mongodb service.",
                cause);
    }
}
