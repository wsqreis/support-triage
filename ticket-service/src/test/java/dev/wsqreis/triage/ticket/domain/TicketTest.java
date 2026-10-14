package dev.wsqreis.triage.ticket.domain;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.Priority;
import dev.wsqreis.triage.contracts.Sentiment;
import dev.wsqreis.triage.contracts.TicketTriaged;
import dev.wsqreis.triage.contracts.TriageSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    static TicketTriaged triage(Ticket ticket, Priority priority, String draft) {
        return new TicketTriaged(ticket.getId(), Category.BILLING, priority, "en", Sentiment.NEGATIVE, "Charged twice.",
                TriageSource.LLM, draft, draft == null ? List.of() : List.of("refunds"), NOW);
    }

    @Test
    void aDraftMakesTheTicketReadyForApproval() {
        var ticket = Ticket.open("ana@example.com", "Charged twice", "Refund please", NOW);

        assertThat(ticket.applyTriage(triage(ticket, Priority.URGENT, "We will refund you."))).isTrue();

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.TRIAGED);
        assertThat(ticket.getPriority()).isEqualTo(Priority.URGENT);
        assertThat(ticket.citationList()).containsExactly("refunds");
    }

    @Test
    void withoutADraftAHumanMustWriteTheReply() {
        var ticket = Ticket.open("ana@example.com", "Dark mode?", "Please", NOW);
        ticket.applyTriage(triage(ticket, Priority.LOW, null));

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.NEEDS_HUMAN);
        assertThatThrownBy(() -> ticket.resolve(null, NOW)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no draft");

        ticket.resolve("It's on our roadmap!", NOW);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(ticket.getFinalReply()).isEqualTo("It's on our roadmap!");
    }

    @Test
    void duplicateTriageEventsAreIgnored() {
        var ticket = Ticket.open("ana@example.com", "Charged twice", "Refund please", NOW);
        ticket.applyTriage(triage(ticket, Priority.URGENT, "We will refund you."));

        assertThat(ticket.applyTriage(triage(ticket, Priority.LOW, "Different"))).isFalse();
        assertThat(ticket.getPriority()).isEqualTo(Priority.URGENT);
    }

    @Test
    void approvingUsesTheDraftAndAnEditReplacesIt() {
        var approved = Ticket.open("a@example.com", "s", "b", NOW);
        approved.applyTriage(triage(approved, Priority.HIGH, "Draft text"));
        approved.resolve(null, NOW);
        assertThat(approved.getFinalReply()).isEqualTo("Draft text");

        var edited = Ticket.open("a@example.com", "s", "b", NOW);
        edited.applyTriage(triage(edited, Priority.HIGH, "Draft text"));
        edited.resolve("Edited text", NOW);
        assertThat(edited.getFinalReply()).isEqualTo("Edited text");
    }

    @Test
    void cannotResolveBeforeTriageOrTwice() {
        var ticket = Ticket.open("a@example.com", "s", "b", NOW);
        assertThatThrownBy(() -> ticket.resolve("x", NOW)).hasMessageContaining("not been triaged");

        ticket.applyTriage(triage(ticket, Priority.HIGH, "Draft"));
        ticket.resolve(null, NOW);
        assertThatThrownBy(() -> ticket.resolve("x", NOW)).hasMessageContaining("already resolved");
    }
}
