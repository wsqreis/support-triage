package dev.wsqreis.triage.triage;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.TicketCreated;
import dev.wsqreis.triage.contracts.TicketTriaged;
import dev.wsqreis.triage.contracts.Topics;
import dev.wsqreis.triage.contracts.TriageSource;
import dev.wsqreis.triage.triage.knowledge.KnowledgeBaseAccess;
import dev.wsqreis.triage.triage.support.HashingEmbeddingModel;
import dev.wsqreis.triage.triage.support.ScriptedChatModel;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The whole service against real Kafka and pgvector; only the LLM and the embedding model are fakes. */
@Import(KnowledgeBaseAccess.class)
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "triage.draft.similarity-threshold=0.1",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
class TriageFlowIT {

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.1");

    // Singleton containers: started once and stopped by Testcontainers' Ryuk when the JVM exits, so they outlive
    // the cached Spring context. Per-class @Container would stop them while scheduled tasks still use them.
    static {
        postgres.start();
        kafka.start();
    }

    private static final List<String> KNOWLEDGE_BASE_ARTICLES = List.of("account-locked", "cancel-subscription",
            "delete-account", "export-notes", "import-notes", "offline-mode", "plans-and-pricing", "recover-deleted-notes",
            "refunds", "report-a-bug", "reset-password", "sync-conflicts", "sync-troubleshooting",
            "two-factor-authentication", "update-payment-method");

    private static final Pattern ARTICLE_ID = Pattern.compile("<article id=\"([^\"]+)\">");

    @TestConfiguration
    static class FakeModels {

        /** Answers the classification prompt with fixed labels and the drafting prompt by citing the first article. */
        @Bean
        @Primary
        ChatModel chatModel() {
            return new ScriptedChatModel(FakeModels::answer);
        }

        @Bean
        @Primary
        EmbeddingModel embeddingModel() {
            return new HashingEmbeddingModel();
        }

        private static String answer(Prompt prompt) {
            var system = prompt.getInstructions().stream()
                    .filter(SystemMessage.class::isInstance).map(m -> m.getText()).findFirst().orElse("");
            if (system.startsWith("You triage")) {
                return """
                        {"category": "ACCOUNT_ACCESS", "priority": "HIGH", "language": "pt", "sentiment": "NEGATIVE",
                         "summary": "Customer forgot the password and the password reset email does not arrive."}
                        """;
            }
            var matcher = ARTICLE_ID.matcher(prompt.getUserMessage().getText());
            var cited = matcher.find() ? matcher.group(1) : "none";
            return """
                    {"answerable": true, "reply": "Olá! Siga os passos do artigo.", "usedArticleIds": ["%s"]}
                    """.formatted(cited);
        }
    }

    @Autowired
    KafkaTemplate<String, String> template;
    @Autowired
    JsonMapper json;
    @Autowired
    KnowledgeBaseAccess knowledgeBase;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void subscribe() {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(Topics.TICKET_TRIAGED, Topics.TICKET_CREATED + "-dlt"));
    }

    @AfterEach
    void close() {
        consumer.close();
    }

    @Test
    void triagesATicketAndPublishesADraftGroundedInTheKnowledgeBase() throws Exception {
        var ticketId = UUID.randomUUID();
        var ticket = new TicketCreated(ticketId, "Esqueci minha senha",
                "Não consigo entrar, o email de recuperação não chega.", Instant.now());

        template.send(Topics.TICKET_CREATED, ticketId.toString(), json.writeValueAsString(ticket)).get();

        var record = awaitRecord(Topics.TICKET_TRIAGED, ticketId.toString());
        var triaged = json.readValue(record.value(), TicketTriaged.class);
        assertThat(triaged.source()).isEqualTo(TriageSource.LLM);
        assertThat(triaged.category()).isEqualTo(Category.ACCOUNT_ACCESS);
        assertThat(triaged.language()).isEqualTo("pt");
        assertThat(triaged.draftReply()).startsWith("Olá!");
        // The draft cites one real article retrieved from pgvector. Which article ranks first depends on the
        // embedding model; the fake one here only checks the wiring, not retrieval quality.
        assertThat(triaged.citations()).hasSize(1);
        assertThat(triaged.citations().getFirst()).isIn(KNOWLEDGE_BASE_ARTICLES);
    }

    @Test
    void sendsMalformedMessagesToTheDeadLetterTopic() throws Exception {
        template.send(Topics.TICKET_CREATED, "broken", "{not json").get();

        var record = awaitRecord(Topics.TICKET_CREATED + "-dlt", "broken");
        assertThat(record.value()).isEqualTo("{not json");
    }

    @Test
    void resyncingTheKnowledgeBaseEmbedsNothingWhenArticlesAreUnchanged() throws Exception {
        var result = knowledgeBase.resync();
        assertThat(result.articles()).isEqualTo(15);
        assertThat(result.embedded()).isZero();
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        var deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            for (var record : consumer.poll(Duration.ofMillis(500))) {
                if (record.topic().equals(topic) && key.equals(record.key())) {
                    return record;
                }
            }
        }
        throw new AssertionError("No record with key " + key + " on " + topic);
    }
}
