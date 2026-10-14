package dev.wsqreis.triage.triage.knowledge;

import dev.wsqreis.triage.contracts.TriageSource;
import dev.wsqreis.triage.triage.classify.Classification;
import dev.wsqreis.triage.triage.llm.LlmGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retrieval-augmented reply drafts. Three gates keep drafts grounded:
 * retrieval must find articles above a similarity threshold, the model must say the articles answer the
 * question, and citations are limited to articles that were actually retrieved.
 * Anything else becomes "no draft", and a human writes the reply.
 */
@Component
public class ReplyDrafter {

    private static final Logger log = LoggerFactory.getLogger(ReplyDrafter.class);

    static final String SYSTEM_PROMPT = """
            You draft replies for Noteflow customer support. A human agent reviews every draft before it is sent.

            Use ONLY the help-center articles provided. Do not invent steps, prices, policies, timelines or
            features that are not in them. If the articles do not answer the customer's question, set
            answerable to false and leave reply empty.

            When answerable:
            - Reply in the customer's language (given as an ISO 639-1 code).
            - Be warm and concise: a short acknowledgement, then the steps or answer.
            - Do not promise anything the articles do not state.
            - usedArticleIds lists the ids of the articles you relied on.
            """;

    private final KnowledgeBase knowledgeBase;
    private final ChatClient chat;
    private final LlmGuard guard;
    private final double similarityThreshold;

    public ReplyDrafter(KnowledgeBase knowledgeBase, ChatClient.Builder builder, LlmGuard guard,
                        @Value("${triage.draft.similarity-threshold:0.45}") double similarityThreshold) {
        this.knowledgeBase = knowledgeBase;
        this.chat = builder.defaultSystem(SYSTEM_PROMPT).build();
        this.guard = guard;
        this.similarityThreshold = similarityThreshold;
    }

    public Optional<Draft> draft(String subject, String body, Classification classification) {
        var articles = knowledgeBase.search(SearchRequest.builder()
                .query(retrievalQuery(subject, body, classification))
                .topK(3)
                .similarityThreshold(similarityThreshold)
                .build());
        if (log.isDebugEnabled()) {
            log.debug("Retrieved for \"{}\": {}", subject, articles.stream()
                    .map(doc -> articleId(doc) + String.format(" (%.2f)", doc.getScore())).toList());
        }
        if (articles.isEmpty()) {
            return Optional.empty();
        }

        LlmDraft result;
        try {
            result = guard.call(() -> chat.prompt()
                    .user(userMessage(subject, body, classification, articles))
                    .call()
                    .entity(LlmDraft.class));
        } catch (Exception e) {
            log.warn("Draft skipped, LLM unavailable ({})", LlmGuard.describe(e));
            return Optional.empty();
        }
        if (result == null || !result.answerable() || result.reply() == null || result.reply().isBlank()) {
            return Optional.empty();
        }

        Set<String> retrieved = articles.stream().map(ReplyDrafter::articleId).collect(Collectors.toSet());
        var citations = result.usedArticleIds() == null ? List.<String>of()
                : result.usedArticleIds().stream().filter(retrieved::contains).distinct().toList();
        if (citations.isEmpty()) {
            // A draft that cites nothing we gave it is not grounded.
            return Optional.empty();
        }
        return Optional.of(new Draft(result.reply().strip(), citations));
    }

    /**
     * The local embedding model only understands English, while customers write in any language.
     * The LLM's classification already includes a one-sentence English summary, so retrieval searches
     * with that; the reply itself is still written in the customer's language.
     */
    static String retrievalQuery(String subject, String body, Classification classification) {
        if (classification.source() == TriageSource.LLM && classification.summary() != null
                && !classification.summary().isBlank()) {
            return classification.summary();
        }
        return subject + "\n" + body;
    }

    private static String userMessage(String subject, String body, Classification c, List<Document> articles) {
        var context = articles.stream()
                .map(doc -> "<article id=\"" + articleId(doc) + "\">\n" + doc.getText() + "\n</article>")
                .collect(Collectors.joining("\n\n"));
        return """
                Help-center articles:
                %s

                Customer language: %s
                Ticket subject: %s
                Ticket body:
                %s
                """.formatted(context, c.language(), subject, body);
    }

    private static String articleId(Document doc) {
        return String.valueOf(doc.getMetadata().get("articleId"));
    }

    record LlmDraft(boolean answerable, String reply, List<String> usedArticleIds) {
    }
}
