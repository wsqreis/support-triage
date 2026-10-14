package dev.wsqreis.triage.ticket.web;

import jakarta.validation.constraints.Size;

/** {@code reply} may be omitted to approve the draft as it is. */
public record ResolveTicketRequest(@Size(max = 10000) String reply) {
}
