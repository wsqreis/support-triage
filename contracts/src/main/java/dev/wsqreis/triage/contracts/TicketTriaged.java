package dev.wsqreis.triage.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by triage-service once a ticket is classified.
 *
 * @param draftReply suggested answer for a human to approve, or {@code null} when the knowledge base had nothing relevant
 * @param citations  ids of the knowledge-base articles the draft is based on
 */
public record TicketTriaged(
        UUID ticketId,
        Category category,
        Priority priority,
        String language,
        Sentiment sentiment,
        String summary,
        TriageSource source,
        String draftReply,
        List<String> citations,
        Instant triagedAt) {
}
