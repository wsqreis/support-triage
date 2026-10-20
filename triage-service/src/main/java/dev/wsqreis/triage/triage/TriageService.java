package dev.wsqreis.triage.triage;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.TicketCreated;
import dev.wsqreis.triage.contracts.TicketTriaged;
import dev.wsqreis.triage.contracts.TriageSource;
import dev.wsqreis.triage.triage.classify.TicketClassifier;
import dev.wsqreis.triage.triage.knowledge.Draft;
import dev.wsqreis.triage.triage.knowledge.ReplyDrafter;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

@Service
public class TriageService {

    private final TicketClassifier classifier;
    private final ReplyDrafter drafter;
    private final Clock clock;

    public TriageService(TicketClassifier classifier, ReplyDrafter drafter, Clock clock) {
        this.classifier = classifier;
        this.drafter = drafter;
        this.clock = clock;
    }

    public TicketTriaged triage(TicketCreated ticket) {
        var classification = classifier.classify(ticket.subject(), ticket.body());
        // Rule-based labels mean the model just failed: asking it for a draft would only wait on it again.
        Optional<Draft> draft = classification.category() == Category.OTHER || classification.source() == TriageSource.RULES
                ? Optional.empty()
                : drafter.draft(ticket.subject(), ticket.body(), classification);
        return new TicketTriaged(
                ticket.ticketId(),
                classification.category(),
                classification.priority(),
                classification.language(),
                classification.sentiment(),
                classification.summary(),
                classification.source(),
                draft.map(Draft::reply).orElse(null),
                draft.map(Draft::citations).orElse(List.of()),
                clock.instant());
    }
}
