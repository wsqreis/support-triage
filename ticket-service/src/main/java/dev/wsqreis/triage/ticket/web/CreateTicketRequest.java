package dev.wsqreis.triage.ticket.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTicketRequest(
        @NotBlank @Email @Size(max = 320) String customerEmail,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 5000) String body) {
}
