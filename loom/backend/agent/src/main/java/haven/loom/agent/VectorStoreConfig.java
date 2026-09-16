package haven.loom.agent;

import com.zaxxer.hikari.HikariDataSource;
import haven.loom.persistence.LockedLiquibase;
import java.sql.SQLException;
import javax.sql.DataSource;
import liquibase.exception.LiquibaseException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two databases, declared explicitly. The moment ANY DataSource bean exists,
 * Boot's datasource autoconfiguration backs off and `spring.datasource.*` is
 * silently ignored — so a lone vectors bean here would quietly become the
 * app's only datasource and Liquibase, JPA and chat memory would all write to
 * the vectors database. Declaring both, with the app database as @Primary,
 * is what keeps every unqualified injection pointed at the right one.
 *
 * Semantic recall lives in its own pgvector-enabled `vectors` database; the
 * app's relational data lives in the primary. They are separate stores.
 */
@Configuration
public class VectorStoreConfig {

    /** Completion marker other beans can depend on (see vectorStore's @DependsOn). */
    public record VectorsMigration() {}

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    DataSource vectorsDataSource(
            @Value("${postgres.vectors-url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .type(HikariDataSource.class)
                .build();
        // Backing services race the app at boot (compose initdb locally, pod
        // ordering in a cell). Hikari retries the first connection for 30s —
        // a bounded wait, then the real error.
        dataSource.setInitializationFailTimeout(30_000);
        return dataSource;
    }

    @Bean
    // LockedLiquibase serializes concurrent api/jobs first boots with a
    // Postgres advisory lock — Liquibase's own lock table cannot serialize its
    // own creation. Changesets are IF NOT EXISTS: the platform pre-installs
    // the vector extension, so even fresh vectors DBs are non-empty.
    VectorsMigration vectorsLiquibase(
            @Qualifier("vectorsDataSource") DataSource vectorsDataSource, ResourceLoader resourceLoader)
            throws SQLException, LiquibaseException {
        LockedLiquibase.migrate(
                vectorsDataSource, resourceLoader, "classpath:db/vectors/changelog.yaml", null, "loom:vectors");
        return new VectorsMigration();
    }

    @Bean
    // Gateway probe first; vectorsLiquibase has created the schema by then. Table
    // validation makes an embedding-dimension drift fail at boot, not at first
    // upsert — the validator compares the CONFIGURED dimension (not the model's),
    // so it must be set explicitly from the model here.
    @org.springframework.context.annotation.DependsOn({"modelsGatewayProbe", "vectorsLiquibase"})
    VectorStore vectorStore(@Qualifier("vectorsDataSource") DataSource vectorsDataSource, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(new JdbcTemplate(vectorsDataSource), embeddingModel)
                .initializeSchema(false)
                .dimensions(embeddingModel.dimensions())
                .vectorTableValidationsEnabled(true)
                .build();
    }
}
