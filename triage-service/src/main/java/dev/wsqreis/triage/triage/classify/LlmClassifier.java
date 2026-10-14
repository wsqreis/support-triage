package dev.wsqreis.triage.triage.classify;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.Priority;
import dev.wsqreis.triage.contracts.Sentiment;
import dev.wsqreis.triage.contracts.TriageSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Structured-output classification: the model fills a typed record, not free text. */
@Component
public class LlmClassifier implements TicketClassifier {

    static final String SYSTEM_PROMPT = """
            You triage support tickets for Noteflow, a note-taking app with Free and Pro plans,
            sync across devices, and export to Markdown and PDF.

            Categories:
            - ACCOUNT_ACCESS: login, password reset, two-factor authentication, locked or deleted accounts
            - BILLING: charges, refunds, invoices, plan upgrades and downgrades, payment methods
            - BUG: something in the app is broken or crashes (not sync, not export)
            - SYNC: notes not syncing, missing on another device, sync conflicts or duplicates
            - DATA_EXPORT: exporting or importing notes, backups, getting data out of Noteflow
            - HOW_TO: questions about how to use a feature
            - FEATURE_REQUEST: asking for something Noteflow does not do yet
            - OTHER: anything else, including spam

            Priority:
            - URGENT: data loss, the customer cannot access their account at all, security concerns, or being charged in error
            - HIGH: a core feature is broken with no workaround, or the customer is blocked from working
            - NORMAL: problems with a workaround, billing or account questions
            - LOW: how-to questions, feature requests, feedback

            language: ISO 639-1 code of the language the customer wrote in, e.g. "en", "pt", "es".
            summary: one sentence in English stating the problem, without greetings or personal data.
            """;

    private final ChatClient chat;

    public LlmClassifier(ChatClient.Builder builder) {
        this.chat = builder.defaultSystem(SYSTEM_PROMPT).build();
    }

    @Override
    public Classification classify(String subject, String body) {
        var result = chat.prompt()
                .user("Subject: " + subject + "\n\n" + body)
                .call()
                .entity(LlmTriage.class);
        if (result == null || result.category() == null || result.priority() == null) {
            throw new IllegalStateException("Model returned an incomplete classification");
        }
        return new Classification(
                result.category(),
                result.priority(),
                result.language() == null ? "en" : result.language().trim().toLowerCase(Locale.ROOT),
                result.sentiment() == null ? Sentiment.NEUTRAL : result.sentiment(),
                result.summary(),
                TriageSource.LLM);
    }

    /** The shape the model is asked to produce. */
    record LlmTriage(Category category, Priority priority, String language, Sentiment sentiment, String summary) {
    }
}
