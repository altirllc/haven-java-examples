package haven.loom.persistence;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * Boots the REAL persistence slice for tests: Boot's autoconfigured Liquibase
 * runs the changelog, then Hibernate validates the entities against it
 * (ddl-auto: validate in src/test/resources/application.yaml). That validation
 * is the schema-drift gate — an entity/changelog mismatch fails the build here,
 * not at first boot in a cell.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PersistenceTestApp {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
