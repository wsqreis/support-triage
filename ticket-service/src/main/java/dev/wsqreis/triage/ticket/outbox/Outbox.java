package dev.wsqreis.triage.ticket.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/** Appends events to the outbox, inside the caller's transaction. */
@Component
public class Outbox {

    private final OutboxRepository repository;
    private final JsonMapper json;
    private final Clock clock;

    public Outbox(OutboxRepository repository, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String key, Object event) {
        repository.save(new OutboxEvent(topic, key, json.writeValueAsString(event), clock.instant()));
    }
}
