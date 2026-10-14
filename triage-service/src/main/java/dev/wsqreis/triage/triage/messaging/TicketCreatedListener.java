package dev.wsqreis.triage.triage.messaging;

import dev.wsqreis.triage.contracts.TicketCreated;
import dev.wsqreis.triage.contracts.Topics;
import dev.wsqreis.triage.triage.TriageService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.TimeUnit;

/**
 * Triage holds no state of its own, so it needs no outbox: if publishing the result fails, the incoming
 * message is retried (and eventually dead-lettered). ticket-service ignores duplicate results.
 */
@Component
public class TicketCreatedListener {

    private final TriageService triage;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    public TicketCreatedListener(TriageService triage, KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.triage = triage;
        this.kafka = kafka;
        this.json = json;
    }

    @KafkaListener(topics = Topics.TICKET_CREATED, groupId = "triage-service")
    public void onTicketCreated(String payload) throws Exception {
        var ticket = json.readValue(payload, TicketCreated.class);
        var result = triage.triage(ticket);
        kafka.send(Topics.TICKET_TRIAGED, ticket.ticketId().toString(), json.writeValueAsString(result))
                .get(10, TimeUnit.SECONDS);
    }
}
