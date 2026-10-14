package dev.wsqreis.triage.ticket.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;
    private String topic;
    @Column(name = "message_key")
    private String key;
    private String payload;
    private Instant createdAt;
    private Instant publishedAt;

    protected OutboxEvent() {
        // for JPA
    }

    OutboxEvent(String topic, String key, String payload, Instant now) {
        this.id = UUID.randomUUID();
        this.topic = topic;
        this.key = key;
        this.payload = payload;
        this.createdAt = now;
    }

    void markPublished(Instant now) {
        this.publishedAt = now;
    }

    public String getTopic() { return topic; }
    public String getKey() { return key; }
    public String getPayload() { return payload; }
    public Instant getPublishedAt() { return publishedAt; }
}
