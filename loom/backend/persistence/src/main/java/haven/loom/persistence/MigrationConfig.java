package haven.loom.persistence;

import java.sql.SQLException;
import javax.sql.DataSource;
import liquibase.exception.LiquibaseException;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/**
 * The app database's DDL, applied at boot: the `loom` schema and then the
 * changelog, both under one pg advisory lock (see LockedLiquibase for why
 * each step races without it). Boot's Liquibase autoconfiguration is not on
 * the classpath (plain liquibase-core, no starter) — it can take no such
 * lock, and under a test @ServiceConnection it would migrate through a raw
 * un-pooled DataSource besides. This bean always migrates through the pool.
 */
@Configuration
public class MigrationConfig {

    /** Completion marker other beans can depend on. */
    public record PrimaryMigration() {}

    @Bean
    PrimaryMigration primaryLiquibase(DataSource dataSource, ResourceLoader resourceLoader)
            throws SQLException, LiquibaseException {
        LockedLiquibase.migrate(
                dataSource,
                resourceLoader,
                "classpath:db/changelog/db.changelog-master.yaml",
                "loom",
                "loom:liquibase");
        return new PrimaryMigration();
    }

    // Hibernate's ddl-auto: validate runs when the EntityManagerFactory is
    // built, which must be AFTER the changelog has been applied. static: a
    // BeanFactoryPostProcessor must not force early creation of this config.
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor entityManagerFactoryDependsOnPrimaryLiquibase() {
        return new EntityManagerFactoryDependsOnPostProcessor("primaryLiquibase");
    }
}
