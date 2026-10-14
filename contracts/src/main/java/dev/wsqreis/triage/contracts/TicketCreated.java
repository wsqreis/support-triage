package dev.wsqreis.triage.contracts;

import java.time.Instant;
import java.util.UUID;

/** Published by ticket-service when a customer opens a ticket. */
public record TicketCreated(UUID ticketId, String subject, String body, Instant createdAt) {
}
