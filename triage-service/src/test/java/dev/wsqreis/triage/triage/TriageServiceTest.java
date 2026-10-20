package dev.wsqreis.triage.triage;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.Priority;
import dev.wsqreis.triage.contracts.Sentiment;
import dev.wsqreis.triage.contracts.TicketCreated;
import dev.wsqreis.triage.contracts.TriageSource;
import dev.wsqreis.triage.triage.classify.Classification;
import dev.wsqreis.triage.triage.knowledge.ReplyDrafter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TriageServiceTest {

    private static final TicketCreated CRASH = new TicketCreated(UUID.randomUUID(), "App crashes when I open a notebook",
            "Since the last update the desktop app closes every time I open my Work notebook.", Instant.now());

    @Test
    void doesNotAskTheModelForADraftRightAfterItFailedToClassify() {
        var drafter = mock(ReplyDrafter.class);
        var service = new TriageService((subject, body) -> new Classification(Category.BUG, Priority.HIGH, "en",
                Sentiment.NEGATIVE, subject, TriageSource.RULES), drafter, Clock.systemUTC());

        var triaged = service.triage(CRASH);

        assertThat(triaged.source()).isEqualTo(TriageSource.RULES);
        assertThat(triaged.draftReply()).isNull();
        verifyNoInteractions(drafter);
    }
}
