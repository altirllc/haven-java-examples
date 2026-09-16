package haven.loom.persistence.mongo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Proves the tenant's MongoDB is reachable at boot — a bad MONGODB_URI kills
 * the process here instead of surfacing at the first (future) query. The
 * platform provisions Mongo in every tenant; loom is wired to it but stores
 * nothing yet, so this ping is deliberately the ONLY Mongo call in the app.
 * The first real document feature starts from Spring Data repositories in this
 * package — and from a working connection.
 */
@Component
public class MongoConnectionCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MongoConnectionCheck.class);

    private final MongoTemplate mongo;

    public MongoConnectionCheck(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void afterPropertiesSet() {
        mongo.executeCommand("{ ping: 1 }");
        log.info("MongoDB reachable (database '{}')", mongo.getDb().getName());
    }
}
