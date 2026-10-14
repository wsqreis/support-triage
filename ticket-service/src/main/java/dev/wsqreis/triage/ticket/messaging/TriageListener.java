package dev.wsqreis.triage.ticket.messaging;

import dev.wsqreis.triage.contracts.TicketTriaged;
import dev.wsqreis.triage.contracts.Topics;
import dev.wsqreis.triage.ticket.application.TicketService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TriageListener {

    private final TicketService tickets;
    private final JsonMapper json;

    public TriageListener(TicketService tickets, JsonMapper json) {
        this.tickets = tickets;
        this.json = json;
    }

    @KafkaListener(topics = Topics.TICKET_TRIAGED, groupId = "ticket-service")
    public void onTriaged(String payload) {
        tickets.applyTriage(json.readValue(payload, TicketTriaged.class));
    }
}
