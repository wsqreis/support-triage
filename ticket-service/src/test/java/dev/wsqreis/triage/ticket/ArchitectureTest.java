package dev.wsqreis.triage.ticket;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(packages = "dev.wsqreis.triage.ticket", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** web and messaging are adapters: both go through the application service, never straight to repositories. */
    @ArchTest
    static final ArchRule layers = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Web").definedBy("..ticket.web..")
            .layer("Messaging").definedBy("..ticket.messaging..")
            .layer("Application").definedBy("..ticket.application..")
            .layer("Outbox").definedBy("..ticket.outbox..")
            .layer("Domain").definedBy("..ticket.domain..")
            .whereLayer("Web").mayNotBeAccessedByAnyLayer()
            .whereLayer("Messaging").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Web", "Messaging")
            .whereLayer("Outbox").mayOnlyBeAccessedByLayers("Application")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Web");

    /** The domain model is plain Java plus JPA mapping: no Spring, no Kafka. */
    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses().that().resideInAPackage("..ticket.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.kafka..", "org.springframework.web..",
                    "org.springframework.stereotype..");
}
