package dev.wsqreis.triage.ticket.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * Moves outbox events to Kafka. Each event is marked published only after Kafka acknowledges it,
 * so delivery is at least once; consumers are idempotent.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka, Clock clock) {
        this.repository = repository;
        this.kafka = kafka;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${outbox.relay-interval:500ms}")
    @Transactional
    public void relay() {
        for (var event : repository.lockNextBatch(BATCH_SIZE)) {
            try {
                kafka.send(event.getTopic(), event.getKey(), event.getPayload()).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Leave the rest for the next run; events keep their order per ticket.
                log.warn("Kafka unavailable, outbox relay will retry: {}", e.getMessage());
                return;
            }
            event.markPublished(clock.instant());
        }
    }
}
