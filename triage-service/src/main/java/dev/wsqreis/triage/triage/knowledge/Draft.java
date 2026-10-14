package dev.wsqreis.triage.triage.knowledge;

import java.util.List;

/** A suggested reply and the knowledge-base articles it is based on. */
public record Draft(String reply, List<String> citations) {
}
