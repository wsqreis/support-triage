package dev.wsqreis.triage.ticket.domain;

import java.util.UUID;

public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(UUID id) {
        super("Ticket " + id + " not found");
    }
}
