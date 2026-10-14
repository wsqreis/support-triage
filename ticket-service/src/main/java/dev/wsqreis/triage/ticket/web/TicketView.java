package dev.wsqreis.triage.ticket.web;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.Priority;
import dev.wsqreis.triage.contracts.Sentiment;
import dev.wsqreis.triage.contracts.TriageSource;
import dev.wsqreis.triage.ticket.domain.Ticket;
import dev.wsqreis.triage.ticket.domain.TicketStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TicketView(
        UUID id,
        String customerEmail,
        String subject,
        String body,
        TicketStatus status,
        Category category,
        Priority priority,
        String language,
        Sentiment sentiment,
        String summary,
        TriageSource triageSource,
        String draftReply,
        List<String> citations,
        String finalReply,
        Instant createdAt,
        Instant triagedAt,
        Instant resolvedAt) {

    static TicketView of(Ticket t) {
        return new TicketView(t.getId(), t.getCustomerEmail(), t.getSubject(), t.getBody(), t.getStatus(),
                t.getCategory(), t.getPriority(), t.getLanguage(), t.getSentiment(), t.getSummary(),
                t.getTriageSource(), t.getDraftReply(), t.citationList(), t.getFinalReply(),
                t.getCreatedAt(), t.getTriagedAt(), t.getResolvedAt());
    }
}
