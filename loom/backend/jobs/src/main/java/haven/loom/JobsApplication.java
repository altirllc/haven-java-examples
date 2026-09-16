package haven.loom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

/**
 * The ingest daemon, the autonomous agent daemon, and the per-case workflows.
 *
 * A worker process: it registers the workflow classes and the activity
 * implementations, then idempotently ensures both long-lived daemon
 * executions are running. Its only listener is the management port (3002),
 * which exists so the cell can probe and scrape it — it serves no
 * application traffic.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"haven.loom.jobs", "haven.loom.persistence", "haven.loom.orchestration", "haven.loom.agent"})
@ConfigurationPropertiesScan({
    "haven.loom.jobs", "haven.loom.persistence", "haven.loom.orchestration", "haven.loom.agent"
})
public class JobsApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobsApplication.class, args);
    }
}
