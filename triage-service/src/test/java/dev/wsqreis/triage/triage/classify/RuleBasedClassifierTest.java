package dev.wsqreis.triage.triage.classify;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.Priority;
import dev.wsqreis.triage.contracts.Sentiment;
import dev.wsqreis.triage.contracts.TriageSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedClassifierTest {

    private final RuleBasedClassifier rules = new RuleBasedClassifier();

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            I forgot my password        | I can't log in since yesterday                   | ACCOUNT_ACCESS | NORMAL
            Charged twice               | I was charged twice for Pro this month           | BILLING        | URGENT
            Notes not syncing           | My phone does not sync with the laptop           | SYNC           | NORMAL
            Export                      | How do I export everything to Markdown?          | DATA_EXPORT    | NORMAL
            Dark mode                   | Would love a dark mode, please add it             | FEATURE_REQUEST| LOW
            Crash                       | The app crashes every time I open it             | BUG            | HIGH
            Question                    | How can I share a notebook with my team?         | HOW_TO         | LOW
            Partnership                 | We sell SEO services                             | OTHER          | NORMAL
            Esqueci a senha             | Não consigo entrar, esqueci minha senha          | ACCOUNT_ACCESS | NORMAL
            """)
    void classifiesByKeywords(String subject, String body, Category category, Priority priority) {
        var result = rules.classify(subject, body);
        assertThat(result.category()).isEqualTo(category);
        assertThat(result.priority()).isEqualTo(priority);
        assertThat(result.source()).isEqualTo(TriageSource.RULES);
    }

    @Test
    void detectsLanguageAndSentiment() {
        assertThat(rules.classify("Sync", "My notes are gone, this is unacceptable").sentiment()).isEqualTo(Sentiment.NEGATIVE);
        assertThat(rules.classify("Obrigado", "Não consigo exportar minhas notas").language()).isEqualTo("pt");
        assertThat(rules.classify("Hola", "No puedo iniciar sesión en mi cuenta").language()).isEqualTo("es");
        assertThat(rules.classify("Hi", "Thanks, great app").language()).isEqualTo("en");
    }

    @Test
    void matchesKeywordsRegardlessOfAccents() {
        assertThat(rules.classify("Sincronização", "As notas não aparecem").category()).isEqualTo(Category.SYNC);
    }
}
