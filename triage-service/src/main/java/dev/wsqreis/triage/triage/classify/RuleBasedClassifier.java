package dev.wsqreis.triage.triage.classify;

import dev.wsqreis.triage.contracts.Category;
import dev.wsqreis.triage.contracts.Priority;
import dev.wsqreis.triage.contracts.Sentiment;
import dev.wsqreis.triage.contracts.TriageSource;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keyword rules: the fallback when the LLM is unavailable, and the baseline the LLM is evaluated against.
 * Deliberately simple and predictable; it covers English, Portuguese and Spanish keywords.
 */
@Component
public class RuleBasedClassifier implements TicketClassifier {

    // Checked in order: the first category with a matching keyword wins.
    private static final Map<Category, List<String>> CATEGORY_KEYWORDS = new LinkedHashMap<>();

    static {
        CATEGORY_KEYWORDS.put(Category.ACCOUNT_ACCESS, List.of("log in", "login", "sign in", "password", "2fa",
                "two-factor", "locked out", "verification code", "senha", "contrasena", "iniciar sesion", "entrar"));
        CATEGORY_KEYWORDS.put(Category.BILLING, List.of("charge", "charged", "refund", "invoice", "receipt", "billing",
                "subscription", "payment", "credit card", "cobran", "reembolso", "fatura", "factura", "cobro"));
        CATEGORY_KEYWORDS.put(Category.SYNC, List.of("sync", "synchron", "not showing on", "other device", "duplicate",
                "conflict", "sincroniz"));
        CATEGORY_KEYWORDS.put(Category.DATA_EXPORT, List.of("export", "import", "backup", "download my notes",
                "markdown", "pdf", "exportar", "importar"));
        CATEGORY_KEYWORDS.put(Category.FEATURE_REQUEST, List.of("feature request", "would be great", "would love",
                "please add", "suggestion", "wish", "sugestao", "sugerencia", "seria otimo", "seria genial"));
        CATEGORY_KEYWORDS.put(Category.BUG, List.of("crash", "bug", "error", "broken", "freezes", "doesn't work",
                "does not work", "not working", "erro", "travando", "no funciona", "falla"));
        CATEGORY_KEYWORDS.put(Category.HOW_TO, List.of("how do i", "how can i", "how to", "is it possible",
                "como faco", "como posso", "como puedo"));
    }

    private static final List<String> URGENT = List.of("lost all", "lost my notes", "data loss", "deleted all",
            "hacked", "charged twice", "double charged", "can't access my account", "cannot access my account",
            "perdi todas", "perdi minhas notas", "cobrado duas vezes", "perdi todas mis");
    private static final List<String> HIGH = List.of("can't work", "cannot work", "urgent", "asap", "every time",
            "all my notes", "deadline", "urgente");
    private static final List<String> NEGATIVE = List.of("frustrat", "angry", "terrible", "unacceptable",
            "disappointed", "ridiculous", "worst", "furious", "pessimo", "horrivel", "inaceptable", "terrible");
    private static final List<String> POSITIVE = List.of("love", "great app", "thank", "awesome", "amazing",
            "obrigad", "gracias", "adoro", "encanta");

    private static final Pattern PORTUGUESE = Pattern.compile("\\b(nao|voce|minha|meu|obrigad\\w*|estou|nota[s]?|conta)\\b");
    private static final Pattern SPANISH = Pattern.compile("\\b(no puedo|gracias|mi|mis|cuenta|estoy|hola|pero)\\b");

    @Override
    public Classification classify(String subject, String body) {
        var text = normalize(subject + " " + body);

        var category = CATEGORY_KEYWORDS.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(text::contains))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(Category.OTHER);

        Priority priority;
        if (URGENT.stream().anyMatch(text::contains)) {
            priority = Priority.URGENT;
        } else if (HIGH.stream().anyMatch(text::contains)) {
            priority = Priority.HIGH;
        } else if (category == Category.HOW_TO || category == Category.FEATURE_REQUEST) {
            priority = Priority.LOW;
        } else {
            priority = Priority.NORMAL;
        }

        Sentiment sentiment = NEGATIVE.stream().anyMatch(text::contains) ? Sentiment.NEGATIVE
                : POSITIVE.stream().anyMatch(text::contains) ? Sentiment.POSITIVE
                : Sentiment.NEUTRAL;

        return new Classification(category, priority, detectLanguage(text), sentiment,
                summarize(subject), TriageSource.RULES);
    }

    private static String detectLanguage(String text) {
        long pt = PORTUGUESE.matcher(text).results().count();
        long es = SPANISH.matcher(text).results().count();
        if (pt == 0 && es == 0) {
            return "en";
        }
        return pt >= es ? "pt" : "es";
    }

    private static String summarize(String subject) {
        var trimmed = subject.strip();
        return trimmed.length() <= 140 ? trimmed : trimmed.substring(0, 137) + "...";
    }

    /** Lowercase without accents, so "sincronização" matches "sincroniz" and "contraseña" matches "contrasena". */
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('’', '\'')
                .toLowerCase(Locale.ROOT);
    }
}
