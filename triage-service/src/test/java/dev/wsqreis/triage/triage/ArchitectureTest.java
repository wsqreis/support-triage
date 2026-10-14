package dev.wsqreis.triage.triage;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "dev.wsqreis.triage.triage", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Classification and drafting know nothing about Kafka: messaging is an adapter around TriageService. */
    @ArchTest
    static final ArchRule coreIsTransportFree = noClasses().that()
            .resideInAnyPackage("..triage.classify..", "..triage.knowledge..", "..triage.llm..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.kafka..", "..triage.messaging..");

    /** Only these two classes talk to the model, and both are only ever called through LlmGuard. */
    @ArchTest
    static final ArchRule chatClientOnlyInGuardedComponents = noClasses().that()
            .doNotHaveSimpleName("LlmClassifier").and().doNotHaveSimpleName("ReplyDrafter")
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.ai.chat.client.ChatClient");

    /** The raw LLM classifier has no timeout or fallback: production code reaches it only via ResilientClassifier. */
    @ArchTest
    static final ArchRule rawLlmClassifierIsWrapped = noClasses().that()
            .doNotHaveSimpleName("ResilientClassifier").and().resideOutsideOfPackage("..triage.eval..")
            .should().dependOnClassesThat().haveSimpleName("LlmClassifier");
}
