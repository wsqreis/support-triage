package dev.wsqreis.triage.triage.knowledge;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.Priority;
import dev.wsqreis.triage.contracts.Sentiment;
import dev.wsqreis.triage.contracts.TriageSource;
import dev.wsqreis.triage.triage.classify.Classification;
import dev.wsqreis.triage.triage.llm.LlmGuard;
import dev.wsqreis.triage.triage.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplyDrafterTest {

    private static final Classification PT_PASSWORD = new Classification(Category.ACCOUNT_ACCESS, Priority.NORMAL, "pt",
            Sentiment.NEUTRAL, "Customer forgot their password and the reset email does not arrive.", TriageSource.LLM);

    private final KnowledgeBase knowledgeBase = mock(KnowledgeBase.class);

    private static Document article(String id) {
        return new Document(KnowledgeBase.idFor(id), "# " + id + "\nSteps...", Map.of("articleId", id));
    }

    private ReplyDrafter drafterAnswering(String json) {
        return new ReplyDrafter(knowledgeBase, ChatClient.builder(ScriptedChatModel.answering(json)),
                new LlmGuard(Duration.ofSeconds(5), Duration.ofMinutes(1)), 0.45);
    }

    @Test
    void draftsFromRetrievedArticlesAndCitesThem() {
        when(knowledgeBase.search(any())).thenReturn(List.of(article("reset-password"), article("account-locked")));
        var drafter = drafterAnswering("""
                {"answerable": true, "reply": "Olá! Use 'Esqueci minha senha'...", "usedArticleIds": ["reset-password"]}
                """);

        var draft = drafter.draft("Esqueci minha senha", "Não recebo o email", PT_PASSWORD);

        assertThat(draft).isPresent();
        assertThat(draft.get().citations()).containsExactly("reset-password");
    }

    @Test
    void searchesWithTheEnglishSummaryWhenTheLlmClassified() {
        when(knowledgeBase.search(any())).thenReturn(List.of());

        drafterAnswering("{}").draft("Esqueci minha senha", "Não recebo o email", PT_PASSWORD);

        verify(knowledgeBase).search(argThat((SearchRequest request) -> request.getQuery().equals(PT_PASSWORD.summary())));
    }

    @Test
    void noDraftWhenNothingRelevantIsRetrieved() {
        when(knowledgeBase.search(any())).thenReturn(List.of());
        assertThat(drafterAnswering("{\"answerable\": true, \"reply\": \"x\"}").draft("Dark mode?", "...", PT_PASSWORD)).isEmpty();
    }

    @Test
    void noDraftWhenTheModelSaysTheArticlesDoNotAnswer() {
        when(knowledgeBase.search(any())).thenReturn(List.of(article("offline-mode")));
        var drafter = drafterAnswering("{\"answerable\": false, \"reply\": \"\", \"usedArticleIds\": []}");
        assertThat(drafter.draft("Dark mode?", "...", PT_PASSWORD)).isEmpty();
    }

    @Test
    void dropsCitationsOfArticlesThatWereNotRetrieved() {
        when(knowledgeBase.search(any())).thenReturn(List.of(article("refunds")));
        var drafter = drafterAnswering("""
                {"answerable": true, "reply": "We will refund you.", "usedArticleIds": ["refunds", "secret-policy"]}
                """);

        assertThat(drafter.draft("Charged twice", "...", PT_PASSWORD).orElseThrow().citations()).containsExactly("refunds");
    }

    @Test
    void noDraftWhenNoCitationIsGrounded() {
        when(knowledgeBase.search(any())).thenReturn(List.of(article("refunds")));
        var drafter = drafterAnswering("""
                {"answerable": true, "reply": "Invented answer.", "usedArticleIds": ["made-up"]}
                """);

        assertThat(drafter.draft("Charged twice", "...", PT_PASSWORD)).isEmpty();
    }

    @Test
    void noDraftWhenTheLlmFails() {
        when(knowledgeBase.search(any())).thenReturn(List.of(article("refunds")));
        var drafter = new ReplyDrafter(knowledgeBase, ChatClient.builder(ScriptedChatModel.failing(new RuntimeException("down"))),
                new LlmGuard(Duration.ofSeconds(5), Duration.ofMinutes(1)), 0.45);

        assertThat(drafter.draft("Charged twice", "...", PT_PASSWORD)).isEmpty();
    }
}
