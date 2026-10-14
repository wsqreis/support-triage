package dev.wsqreis.triage.ticket.domain;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.Priority;
import dev.wsqreis.triage.contracts.Sentiment;
import dev.wsqreis.triage.contracts.TicketTriaged;
import dev.wsqreis.triage.contracts.TriageSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    private UUID id;
    private String customerEmail;
    private String subject;
    private String body;

    @Enumerated(EnumType.STRING)
    private TicketStatus status;

    @Enumerated(EnumType.STRING)
    private Category category;
    @Enumerated(EnumType.STRING)
    private Priority priority;
    private int priorityRank = -1;
    private String language;
    @Enumerated(EnumType.STRING)
    private Sentiment sentiment;
    private String summary;
    @Enumerated(EnumType.STRING)
    @Column(name = "triage_source")
    private TriageSource triageSource;
    private String draftReply;
    private String citations;
    private String finalReply;

    private Instant createdAt;
    private Instant triagedAt;
    private Instant resolvedAt;

    @Version
    private long version;

    protected Ticket() {
        // for JPA
    }

    public static Ticket open(String customerEmail, String subject, String body, Instant now) {
        var ticket = new Ticket();
        ticket.id = UUID.randomUUID();
        ticket.customerEmail = customerEmail;
        ticket.subject = subject;
        ticket.body = body;
        ticket.status = TicketStatus.NEW;
        ticket.createdAt = now;
        return ticket;
    }

    /**
     * Records the triage result. Kafka delivers at least once, so the same event may arrive twice:
     * only the first one for a NEW ticket is applied.
     *
     * @return whether the ticket changed
     */
    public boolean applyTriage(TicketTriaged triage) {
        if (status != TicketStatus.NEW) {
            return false;
        }
        category = triage.category();
        priority = triage.priority();
        priorityRank = triage.priority().ordinal();
        language = triage.language();
        sentiment = triage.sentiment();
        summary = triage.summary();
        triageSource = triage.source();
        draftReply = triage.draftReply();
        citations = triage.citations() == null ? null : String.join("\n", triage.citations());
        triagedAt = triage.triagedAt();
        status = draftReply == null || draftReply.isBlank() ? TicketStatus.NEEDS_HUMAN : TicketStatus.TRIAGED;
        return true;
    }

    /**
     * A human closes the ticket. Nothing is ever sent to a customer without this step.
     *
     * @param reply the text to send; {@code null} approves the draft as it is
     */
    public void resolve(String reply, Instant now) {
        if (status == TicketStatus.NEW) {
            throw new IllegalStateException("Ticket " + id + " has not been triaged yet");
        }
        if (status == TicketStatus.RESOLVED) {
            throw new IllegalStateException("Ticket " + id + " is already resolved");
        }
        var text = reply == null || reply.isBlank() ? draftReply : reply;
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Ticket " + id + " has no draft; write a reply to resolve it");
        }
        finalReply = text;
        resolvedAt = now;
        status = TicketStatus.RESOLVED;
    }

    public List<String> citationList() {
        return citations == null || citations.isBlank() ? List.of() : Arrays.asList(citations.split("\n"));
    }

    public UUID getId() { return id; }
    public String getCustomerEmail() { return customerEmail; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public TicketStatus getStatus() { return status; }
    public Category getCategory() { return category; }
    public Priority getPriority() { return priority; }
    public String getLanguage() { return language; }
    public Sentiment getSentiment() { return sentiment; }
    public String getSummary() { return summary; }
    public TriageSource getTriageSource() { return triageSource; }
    public String getDraftReply() { return draftReply; }
    public String getFinalReply() { return finalReply; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getTriagedAt() { return triagedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
