package haven.loom;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.temporal.workflow.WorkflowInterface;
import org.junit.jupiter.api.Test;

/**
 * Rules the Maven module graph cannot express, checked from jobs — the one
 * module that sees every other.
 */
class ArchitectureTest {

    private static final JavaClasses ALL = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(new ImportOption.DoNotIncludeJars())
            .importPackages("haven.loom");

    /**
     * Closes the hole in the determinism fence. :jobs must depend on both
     * :workflows and :persistence to wire the worker, so a workflow implemented
     * HERE would compile with the items service in scope and silently escape the
     * fence entirely. Nothing in the module graph forces implementations to live
     * in :workflows — this does.
     */
    @Test
    void workflowsAreOnlyImplementedInTheFencedModule() {
        noClasses()
                .that()
                .resideOutsideOfPackage("haven.loom.workflows..")
                .should()
                .implement(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "an interface annotated with @WorkflowInterface",
                        javaClass -> javaClass.isAnnotatedWith(WorkflowInterface.class)))
                .because("Workflow implementations must live in :workflows, whose classpath physically excludes "
                        + "Hibernate, Spring and the Temporal client. Implementing one anywhere else compiles fine "
                        + "and disables the determinism fence without any signal.")
                .check(ALL);
    }

    /**
     * The JPA entities and repositories are the DBAL, and the items service is
     * their sole gate — one definition of create/update/decide, one audit-log
     * shape.
     */
    @Test
    void onlyTheItemsServiceTouchesTheJpaLayer() {
        noClasses()
                .that()
                .resideOutsideOfPackage("haven.loom.persistence..")
                .should()
                .accessClassesThat()
                .resideInAPackage("haven.loom.persistence.jpa..")
                .because("Every read and write goes through CaseService, so there is one definition of how an item "
                        + "is created/updated/decided and one audit-log shape.")
                .check(ALL);
    }

    /**
     * Entities and repositories must not leak out of :persistence in NEW
     * packages either — the data layer's surface area is CaseService, not
     * whatever JPA machinery a feature finds convenient.
     */
    @Test
    void onlyThePersistenceModuleUsesJpaAndSpringData() {
        noClasses()
                .that()
                .resideOutsideOfPackage("haven.loom.persistence..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "org.springframework.data..")
                .because("The persistence module is the only place entities and repositories may exist; everything "
                        + "else calls CaseService.")
                .check(ALL);
    }
}
