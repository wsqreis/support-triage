package dev.wsqreis.triage.triage.classify;

import dev.wsqreis.triage.triage.llm.LlmGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The LLM classifier with the rules as fallback. A slow, failing or unconfigured model never stops
 * tickets from being triaged: they get rule-based labels instead, marked with source RULES.
 */
@Primary
@Component
public class ResilientClassifier implements TicketClassifier {

    private static final Logger log = LoggerFactory.getLogger(ResilientClassifier.class);

    private final LlmClassifier llm;
    private final RuleBasedClassifier rules;
    private final LlmGuard guard;

    public ResilientClassifier(LlmClassifier llm, RuleBasedClassifier rules, LlmGuard guard) {
        this.llm = llm;
        this.rules = rules;
        this.guard = guard;
    }

    @Override
    public Classification classify(String subject, String body) {
        try {
            return guard.call(() -> llm.classify(subject, body));
        } catch (Exception e) {
            log.warn("LLM classification unavailable ({}), falling back to rules", LlmGuard.describe(e));
            return rules.classify(subject, body);
        }
    }
}
