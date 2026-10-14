package dev.wsqreis.triage.triage.classify;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.Priority;
import dev.wsqreis.triage.contracts.TriageSource;
import dev.wsqreis.triage.triage.llm.LlmGuard;
import dev.wsqreis.triage.triage.support.ScriptedChatModel;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ResilientClassifierTest {

    private static final String VALID = """
            {"category": "BILLING", "priority": "URGENT", "language": "EN", "sentiment": "NEGATIVE",
             "summary": "Customer was charged twice."}
            """;

    private static ResilientClassifier classifierWith(ScriptedChatModel model, Duration timeout) {
        var llm = new LlmClassifier(ChatClient.builder(model));
        return new ResilientClassifier(llm, new RuleBasedClassifier(), new LlmGuard(timeout, Duration.ofMinutes(1)));
    }

    @Test
    void usesTheLlmWhenItAnswers() {
        var result = classifierWith(ScriptedChatModel.answering(VALID), Duration.ofSeconds(5))
                .classify("Charged twice", "Please refund one");

        assertThat(result.source()).isEqualTo(TriageSource.LLM);
        assertThat(result.category()).isEqualTo(Category.BILLING);
        assertThat(result.priority()).isEqualTo(Priority.URGENT);
        assertThat(result.language()).isEqualTo("en");
    }

    @Test
    void fallsBackToRulesWhenTheLlmFails() {
        var result = classifierWith(ScriptedChatModel.failing(new RuntimeException("401 Unauthorized")), Duration.ofSeconds(5))
                .classify("Charged twice", "I was charged twice");

        assertThat(result.source()).isEqualTo(TriageSource.RULES);
        assertThat(result.category()).isEqualTo(Category.BILLING);
    }

    @Test
    void fallsBackToRulesWhenTheLlmIsTooSlow() {
        var slow = new ScriptedChatModel(prompt -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return VALID;
        });
        var started = System.nanoTime();

        var result = classifierWith(slow, Duration.ofMillis(200)).classify("Charged twice", "I was charged twice");

        assertThat(result.source()).isEqualTo(TriageSource.RULES);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void fallsBackWhenTheModelReturnsAnIncompleteAnswer() {
        var result = classifierWith(ScriptedChatModel.answering("{\"summary\": \"no labels\"}"), Duration.ofSeconds(5))
                .classify("Export", "How do I export my notes?");

        assertThat(result.source()).isEqualTo(TriageSource.RULES);
    }

    @Test
    void stopsCallingAFailingModelOnceTheCircuitOpens() {
        var model = ScriptedChatModel.failing(new RuntimeException("503 Service Unavailable"));
        var guard = new LlmGuard(Duration.ofSeconds(5), Duration.ofMinutes(1));
        var classifier = new ResilientClassifier(new LlmClassifier(ChatClient.builder(model)), new RuleBasedClassifier(), guard);

        for (int i = 0; i < 10; i++) {
            assertThat(classifier.classify("Crash", "The app crashes").source()).isEqualTo(TriageSource.RULES);
        }

        assertThat(guard.state()).isEqualTo(CircuitBreaker.State.OPEN);
        // Four failed calls open the circuit; the remaining six never reach the model.
        assertThat(model.calls()).isEqualTo(4);
    }
}
