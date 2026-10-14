package dev.wsqreis.triage.ticket.domain;

public enum TicketStatus {
    /** Waiting for triage. */
    NEW,
    /** Classified, with a draft reply ready for a human to approve. */
    TRIAGED,
    /** Classified, but the knowledge base had no answer: a human has to write the reply. */
    NEEDS_HUMAN,
    RESOLVED
}
