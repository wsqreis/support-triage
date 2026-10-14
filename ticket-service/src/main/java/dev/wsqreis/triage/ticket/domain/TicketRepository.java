package dev.wsqreis.triage.ticket.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    /** The support queue: most urgent first, then oldest first. */
    List<Ticket> findTop100ByStatusInOrderByPriorityRankDescCreatedAtAsc(Collection<TicketStatus> statuses);
}
