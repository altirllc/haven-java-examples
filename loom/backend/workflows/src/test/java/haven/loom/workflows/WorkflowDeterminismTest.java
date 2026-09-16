package haven.loom.workflows;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The half of the determinism guardrail the module graph cannot enforce:
 * java.base is on every classpath, so nothing stops Instant.now() or
 * UUID.randomUUID() — the actual nondeterminism sources. Plain ArchUnit with
 * ordinary @Test methods (JUnit 6 has no archunit binding).
 */
class WorkflowDeterminismTest {

    private static final String WHY = "Temporal's Java SDK has no workflow sandbox, so nondeterminism here corrupts "
            + "replay silently — it passes every test and fails in production weeks later. Use "
            + "Workflow.currentTimeMillis(), Workflow.randomUUID(), Workflow.newRandom(), Workflow.sleep(), and do "
            + "all IO in an activity.";

    private static final JavaClasses WORKFLOW_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("haven.loom.workflows");

    @Test
    void workflowsDoNotReadTheClock() {
        noClasses()
                .should()
                .callMethod(System.class, "currentTimeMillis")
                .orShould()
                .callMethod(System.class, "nanoTime")
                .orShould()
                .callMethod(Instant.class, "now")
                .orShould()
                .callMethod(LocalDate.class, "now")
                .orShould()
                .callMethod(LocalDateTime.class, "now")
                .orShould()
                .callMethod(OffsetDateTime.class, "now")
                .orShould()
                .callMethod(ZonedDateTime.class, "now")
                .because(WHY)
                .check(WORKFLOW_CLASSES);
    }

    @Test
    void workflowsDoNotGenerateRandomness() {
        noClasses()
                .should()
                .callMethod(UUID.class, "randomUUID")
                .orShould()
                .callMethod(Math.class, "random")
                .orShould()
                .callConstructor(Random.class)
                .because(WHY)
                .check(WORKFLOW_CLASSES);
    }

    @Test
    void workflowsDoNotTouchThreadsOrTheEnvironment() {
        noClasses()
                .should()
                .callMethod(Thread.class, "sleep", long.class)
                .orShould()
                .callConstructor(Thread.class)
                .orShould()
                .callMethod(System.class, "getenv")
                .orShould()
                .callMethod(System.class, "getenv", String.class)
                .because(WHY)
                .check(WORKFLOW_CLASSES);
    }

    @Test
    void workflowsDoNotPerformIo() {
        noClasses()
                .should()
                .accessClassesThat()
                .resideInAnyPackage("java.io..", "java.net..", "java.nio.file..", "java.sql..", "javax.sql..")
                .because(WHY)
                .check(WORKFLOW_CLASSES);
    }
}
