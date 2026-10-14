package dev.wsqreis.triage.ticket.application;

import dev.wsqreis.triage.contracts.TicketCreated;
import dev.wsqreis.triage.contracts.TicketTriaged;
import dev.wsqreis.triage.contracts.Topics;
import dev.wsqreis.triage.ticket.domain.Ticket;
import dev.wsqreis.triage.ticket.domain.TicketNotFoundException;
import dev.wsqreis.triage.ticket.domain.TicketRepository;
import dev.wsqreis.triage.ticket.domain.TicketStatus;
import dev.wsqreis.triage.ticket.outbox.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository tickets;
    private final Outbox outbox;
    private final Clock clock;

    public TicketService(TicketRepository tickets, Outbox outbox, Clock clock) {
        this.tickets = tickets;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Saves the ticket and its TicketCreated event atomically. */
    @Transactional
    public Ticket open(String customerEmail, String subject, String body) {
        var ticket = tickets.save(Ticket.open(customerEmail, subject, body, clock.instant()));
        outbox.append(Topics.TICKET_CREATED, ticket.getId().toString(),
                new TicketCreated(ticket.getId(), subject, body, ticket.getCreatedAt()));
        return ticket;
    }

    @Transactional
    public void applyTriage(TicketTriaged triage) {
        var ticket = tickets.findById(triage.ticketId()).orElse(null);
        if (ticket == null) {
            log.warn("Triage for unknown ticket {} ignored", triage.ticketId());
            return;
        }
        if (!ticket.applyTriage(triage)) {
            log.info("Duplicate triage for ticket {} ignored", triage.ticketId());
        }
    }

    @Transactional
    public Ticket resolve(UUID id, String reply) {
        var ticket = get(id);
        ticket.resolve(reply, clock.instant());
        return ticket;
    }

    @Transactional(readOnly = true)
    public Ticket get(UUID id) {
        return tickets.findById(id).orElseThrow(() -> new TicketNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Ticket> queue() {
        return tickets.findTop100ByStatusInOrderByPriorityRankDescCreatedAtAsc(
                EnumSet.of(TicketStatus.TRIAGED, TicketStatus.NEEDS_HUMAN));
    }
}
