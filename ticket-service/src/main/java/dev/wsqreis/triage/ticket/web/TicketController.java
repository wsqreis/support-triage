package dev.wsqreis.triage.ticket.web;

import dev.wsqreis.triage.ticket.application.TicketService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService tickets;

    public TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping
    public ResponseEntity<TicketView> open(@Valid @RequestBody CreateTicketRequest request) {
        var ticket = tickets.open(request.customerEmail(), request.subject(), request.body());
        return ResponseEntity.created(URI.create("/api/tickets/" + ticket.getId())).body(TicketView.of(ticket));
    }

    @GetMapping("/{id}")
    public TicketView get(@PathVariable UUID id) {
        return TicketView.of(tickets.get(id));
    }

    /** Tickets waiting for a human, most urgent first. */
    @GetMapping("/queue")
    public List<TicketView> queue() {
        return tickets.queue().stream().map(TicketView::of).toList();
    }

    @PostMapping("/{id}/resolve")
    public TicketView resolve(@PathVariable UUID id, @Valid @RequestBody(required = false) ResolveTicketRequest request) {
        return TicketView.of(tickets.resolve(id, request == null ? null : request.reply()));
    }
}
