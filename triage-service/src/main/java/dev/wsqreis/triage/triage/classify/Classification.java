package dev.wsqreis.triage.triage.classify;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.Priority;
import dev.wsqreis.triage.contracts.Sentiment;
import dev.wsqreis.triage.contracts.TriageSource;

public record Classification(
        Category category,
        Priority priority,
        String language,
        Sentiment sentiment,
        String summary,
        TriageSource source) {
}
