package dev.wsqreis.triage.ticket;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.Priority;
import dev.wsqreis.triage.contracts.Sentiment;
import dev.wsqreis.triage.contracts.TicketCreated;
import dev.wsqreis.triage.contracts.TicketTriaged;
import dev.wsqreis.triage.contracts.Topics;
import dev.wsqreis.triage.contracts.TriageSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** The service over HTTP, against real Postgres and Kafka. triage-service is played by the test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "outbox.relay-interval=100ms")
class TicketFlowIT {

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.1");

    // Singleton containers: started once and stopped by Testcontainers' Ryuk when the JVM exits, so they outlive
    // the cached Spring context. Per-class @Container would stop them while scheduled tasks still use them.
    static {
        postgres.start();
        kafka.start();
    }

    @LocalServerPort
    int port;
    @Autowired
    JsonMapper json;
    @Autowired
    KafkaTemplate<String, String> template;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void ticketLifecycle_openTriageApprove() throws Exception {
        // 1. A customer opens a ticket: it is stored, and TicketCreated reaches Kafka through the outbox.
        var created = post("/api/tickets", """
                {"customerEmail": "ana@example.com", "subject": "Charged twice", "body": "Please refund one charge."}
                """);
        assertThat(created.statusCode()).isEqualTo(201);
        var id = UUID.fromString(json.readTree(created.body()).get("id").asString());

        var event = awaitEvent(Topics.TICKET_CREATED, id);
        assertThat(event.subject()).isEqualTo("Charged twice");

        // 2. Triage arrives (twice: Kafka is at-least-once) and the ticket joins the queue.
        var triaged = new TicketTriaged(id, Category.BILLING, Priority.URGENT, "en", Sentiment.NEGATIVE,
                "Customer was charged twice.", TriageSource.LLM, "We'll refund the duplicate charge.",
                List.of("refunds"), Instant.now());
        publishTriage(triaged);
        publishTriage(new TicketTriaged(id, Category.OTHER, Priority.LOW, "en", Sentiment.NEUTRAL, "dup",
                TriageSource.RULES, null, List.of(), Instant.now()));

        var ticket = awaitTicket(id, t -> t.get("status").asString().equals("TRIAGED"));
        assertThat(ticket.get("priority").asString()).isEqualTo("URGENT");
        assertThat(ticket.get("draftReply").asString()).isEqualTo("We'll refund the duplicate charge.");
        assertThat(ticket.get("citations").get(0).asString()).isEqualTo("refunds");
        Thread.sleep(500); // give the duplicate time to arrive, then check it changed nothing
        assertThat(get("/api/tickets/" + id).get("category").asString()).isEqualTo("BILLING");

        // 3. An agent approves the draft; resolving twice is a conflict.
        var resolved = post("/api/tickets/" + id + "/resolve", "{}");
        assertThat(resolved.statusCode()).isEqualTo(200);
        assertThat(json.readTree(resolved.body()).get("finalReply").asString()).isEqualTo("We'll refund the duplicate charge.");
        assertThat(post("/api/tickets/" + id + "/resolve", "{}").statusCode()).isEqualTo(409);
    }

    @Test
    void queueShowsMostUrgentFirst() throws Exception {
        var low = openAndTriage("Dark mode?", Priority.LOW);
        var urgent = openAndTriage("Lost all my notes", Priority.URGENT);
        var normal = openAndTriage("How do I export?", Priority.NORMAL);

        var queue = json.readTree(http.send(request("/api/tickets/queue").build(), HttpResponse.BodyHandlers.ofString()).body());
        var order = queue.valueStream().map(t -> UUID.fromString(t.get("id").asString()))
                .filter(id -> id.equals(low) || id.equals(urgent) || id.equals(normal)).toList();
        assertThat(order).containsExactly(urgent, normal, low);
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        assertThat(post("/api/tickets", """
                {"customerEmail": "not-an-email", "subject": "", "body": "x"}
                """).statusCode()).isEqualTo(400);
        assertThat(http.send(request("/api/tickets/" + UUID.randomUUID()).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(404);
    }

    private UUID openAndTriage(String subject, Priority priority) throws Exception {
        var response = post("/api/tickets", """
                {"customerEmail": "c@example.com", "subject": "%s", "body": "..."}
                """.formatted(subject));
        var id = UUID.fromString(json.readTree(response.body()).get("id").asString());
        publishTriage(new TicketTriaged(id, Category.OTHER, priority, "en", Sentiment.NEUTRAL, subject,
                TriageSource.LLM, null, List.of(), Instant.now()));
        awaitTicket(id, t -> !t.get("status").asString().equals("NEW"));
        return id;
    }

    private void publishTriage(TicketTriaged triaged) throws Exception {
        template.send(Topics.TICKET_TRIAGED, triaged.ticketId().toString(), json.writeValueAsString(triaged)).get();
    }

    private TicketCreated awaitEvent(String topic, UUID id) {
        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            var deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (id.toString().equals(record.key())) {
                        return json.readValue(record.value(), TicketCreated.class);
                    }
                }
            }
        }
        throw new AssertionError("TicketCreated for " + id + " never reached Kafka");
    }

    private JsonNode awaitTicket(UUID id, Predicate<JsonNode> condition) throws Exception {
        var deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            var ticket = get("/api/tickets/" + id);
            if (condition.test(ticket)) {
                return ticket;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Ticket " + id + " never reached the expected state");
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("content-type", "application/json");
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(request(path).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String path) throws Exception {
        return json.readTree(http.send(request(path).build(), HttpResponse.BodyHandlers.ofString()).body());
    }
}
