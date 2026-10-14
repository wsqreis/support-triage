package dev.wsqreis.triage.triage.knowledge;

import org.springframework.boot.test.context.TestComponent;

import java.io.IOException;

/** Lets tests outside this package trigger a knowledge-base sync and read its counts. */
@TestComponent
public class KnowledgeBaseAccess {

    private final KnowledgeBase knowledgeBase;

    public KnowledgeBaseAccess(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public KnowledgeBase.SyncResult resync() throws IOException {
        return knowledgeBase.sync();
    }
}
