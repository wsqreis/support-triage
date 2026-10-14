package dev.wsqreis.triage.ticket.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final OutboxRelay relay = new OutboxRelay(repository, kafka, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void marksEventsPublishedOnlyAfterKafkaAcknowledges() {
        var first = new OutboxEvent("tickets.created", "t1", "{}", NOW);
        var second = new OutboxEvent("tickets.created", "t2", "{}", NOW);
        when(repository.lockNextBatch(100)).thenReturn(List.of(first, second));
        when(kafka.send(anyString(), eq("t1"), anyString())).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(kafka.send(anyString(), eq("t2"), anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        relay.relay();

        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).as("kept for the next run").isNull();
    }

    @Test
    void stopsAtTheFirstFailureToKeepPerTicketOrder() {
        var first = new OutboxEvent("tickets.created", "t1", "{}", NOW);
        var second = new OutboxEvent("tickets.created", "t1", "{}", NOW);
        when(repository.lockNextBatch(100)).thenReturn(List.of(first, second));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        relay.relay();

        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
        assertThat(first.getPublishedAt()).isNull();
        assertThat(second.getPublishedAt()).isNull();
    }
}
