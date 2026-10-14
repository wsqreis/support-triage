package dev.wsqreis.triage.triage.eval;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.Priority;
import dev.wsqreis.triage.triage.classify.Classification;
import dev.wsqreis.triage.triage.classify.LlmClassifier;
import dev.wsqreis.triage.triage.classify.RuleBasedClassifier;
import dev.wsqreis.triage.triage.classify.TicketClassifier;
import dev.wsqreis.triage.triage.support.HashingEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accuracy of the LLM classifier against the keyword rules, on 30 hand-labelled tickets.
 * Not part of the build: run it on demand (it calls the real model, once per ticket):
 *
 * <pre>OPENROUTER_API_KEY=... ./mvnw -pl triage-service -am test -Dtest=ClassifierEval -Dsurefire.failIfNoSpecifiedTests=false</pre>
 *
 * Without OPENROUTER_API_KEY only the rules are evaluated. Results go to triage-service/target/eval/.
 */
@SpringBootTest(properties = {"spring.ai.model.embedding=none", "spring.kafka.listener.auto-startup=false"})
class ClassifierEval {

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.1");

    static {
        postgres.start();
        kafka.start();
    }

    @TestConfiguration
    static class LocalEmbeddings {
        @Bean
        EmbeddingModel embeddingModel() {
            return new HashingEmbeddingModel();
        }
    }

    record Labelled(String id, String subject, String body, Category category, Priority priority, String language) {
    }

    record Outcome(String id, Category category, Priority priority, String language, String error, long millis) {
    }

    @Autowired
    RuleBasedClassifier rules;
    @Autowired
    LlmClassifier llm;
    @Autowired
    JsonMapper json;

    @Test
    void compareClassifiers() throws Exception {
        List<Labelled> tickets = json.readValue(new ClassPathResource("eval/tickets.json").getInputStream(),
                new TypeReference<>() {
                });
        var report = new LinkedHashMap<String, Object>();
        report.put("ranAt", Instant.now().toString());
        report.put("tickets", tickets.size());

        report.put("rules", evaluate("rules", rules, tickets));
        var key = System.getenv("OPENROUTER_API_KEY");
        if (key != null && !key.isBlank()) {
            report.put("llm", evaluate("llm (" + System.getenv().getOrDefault("LLM_MODEL", "default model") + ")", llm, tickets));
        } else {
            System.out.println("\nOPENROUTER_API_KEY not set: evaluated the rules only.");
        }

        var dir = Path.of("target", "eval");
        Files.createDirectories(dir);
        var file = dir.resolve("classifier-eval-" + System.currentTimeMillis() + ".json");
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Full results: " + file.toAbsolutePath());
    }

    private Map<String, Object> evaluate(String name, TicketClassifier classifier, List<Labelled> tickets) throws InterruptedException {
        var outcomes = new ArrayList<Outcome>();
        int category = 0, priority = 0, priorityWithinOne = 0, language = 0, errors = 0, urgent = 0, urgentCaught = 0;
        long totalMillis = 0;

        for (var ticket : tickets) {
            var started = System.nanoTime();
            Classification result = null;
            String error = null;
            try {
                result = classifier.classify(ticket.subject(), ticket.body());
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                errors++;
            }
            long millis = (System.nanoTime() - started) / 1_000_000;
            totalMillis += millis;

            if (ticket.priority() == Priority.URGENT) {
                urgent++;
            }
            if (result != null) {
                if (result.category() == ticket.category()) category++;
                if (result.priority() == ticket.priority()) priority++;
                if (Math.abs(result.priority().ordinal() - ticket.priority().ordinal()) <= 1) priorityWithinOne++;
                if (ticket.language().equals(result.language())) language++;
                if (ticket.priority() == Priority.URGENT && result.priority() == Priority.URGENT) urgentCaught++;
            }
            outcomes.add(new Outcome(ticket.id(), result == null ? null : result.category(),
                    result == null ? null : result.priority(), result == null ? null : result.language(), error, millis));
            if (classifier == llm) {
                Thread.sleep(1500); // stay under free-tier rate limits
            }
        }

        int n = tickets.size();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("categoryAccuracy", pct(category, n));
        summary.put("priorityExact", pct(priority, n));
        summary.put("priorityWithinOneLevel", pct(priorityWithinOne, n));
        summary.put("urgentRecall", pct(urgentCaught, urgent));
        summary.put("languageAccuracy", pct(language, n));
        summary.put("errors", errors);
        summary.put("avgMillis", totalMillis / n);

        System.out.printf("%n== %s%n", name);
        summary.forEach((k, v) -> System.out.printf("  %-24s %s%n", k, v));
        for (int i = 0; i < n; i++) {
            var expected = tickets.get(i);
            var got = outcomes.get(i);
            if (got.category() != expected.category() || got.priority() != expected.priority()) {
                System.out.printf("  miss %-22s expected %s/%s, got %s/%s%s%n", expected.id(), expected.category(),
                        expected.priority(), got.category(), got.priority(), got.error() == null ? "" : " (" + got.error() + ")");
            }
        }
        return Map.of("summary", summary, "outcomes", outcomes);
    }

    private static String pct(int part, int total) {
        return total == 0 ? "-" : Math.round(100.0 * part / total) + "% (" + part + "/" + total + ")";
    }
}
