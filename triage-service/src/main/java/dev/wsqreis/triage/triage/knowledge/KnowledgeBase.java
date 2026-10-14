package dev.wsqreis.triage.triage.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Keeps the vector store in sync with the help-center articles in {@code classpath:knowledge/*.md}.
 *
 * <p>Runs as a {@link SmartInitializingSingleton}: after all beans exist but before Kafka listeners start,
 * so no ticket is triaged against a half-loaded knowledge base. Only new or changed articles are embedded,
 * and articles removed from the folder are removed from the store.
 */
@Component
public class KnowledgeBase implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    private final VectorStore vectorStore;
    private final ResourcePatternResolver resources;
    private final JdbcTemplate jdbc;

    public KnowledgeBase(VectorStore vectorStore, ResourcePatternResolver resources, JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.resources = resources;
        this.jdbc = jdbc;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            sync();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load the knowledge base", e);
        }
    }

    SyncResult sync() throws IOException {
        var current = new HashMap<String, Document>();
        for (Resource resource : resources.getResources("classpath:knowledge/*.md")) {
            var document = toDocument(resource);
            current.put(document.getId(), document);
        }

        // id -> content hash of what is stored now. The table holds only the knowledge base in this service.
        var stored = new HashMap<String, String>();
        jdbc.query("SELECT id::text, metadata->>'hash' FROM vector_store",
                row -> {
                    stored.put(row.getString(1), row.getString(2));
                });

        var changed = new ArrayList<Document>();
        for (var document : current.values()) {
            if (!Objects.equals(stored.get(document.getId()), document.getMetadata().get("hash"))) {
                changed.add(document);
            }
        }
        var removed = stored.keySet().stream().filter(id -> !current.containsKey(id)).toList();

        var stale = new ArrayList<String>(removed);
        changed.forEach(document -> stale.add(document.getId()));
        if (!stale.isEmpty()) {
            vectorStore.delete(stale);
        }
        if (!changed.isEmpty()) {
            vectorStore.add(changed);
        }
        log.info("Knowledge base ready: {} articles ({} embedded, {} removed, {} unchanged)",
                current.size(), changed.size(), removed.size(), current.size() - changed.size());
        return new SyncResult(current.size(), changed.size(), removed.size());
    }

    public record SyncResult(int articles, int embedded, int removed) {
    }

    public List<Document> search(SearchRequest request) {
        return vectorStore.similaritySearch(request);
    }

    static Document toDocument(Resource resource) throws IOException {
        var articleId = Objects.requireNonNull(resource.getFilename()).replaceFirst("\\.md$", "");
        var text = resource.getContentAsString(StandardCharsets.UTF_8);
        var title = text.lines().filter(line -> line.startsWith("# ")).findFirst()
                .map(line -> line.substring(2).strip()).orElse(articleId);
        Map<String, Object> metadata = Map.of("source", "kb", "articleId", articleId, "title", title, "hash", sha256(text));
        return new Document(idFor(articleId), text, metadata);
    }

    /** pgvector stores ids as UUIDs, so article names are mapped to name-based UUIDs. */
    static String idFor(String articleId) {
        return UUID.nameUUIDFromBytes(("kb:" + articleId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String sha256(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
