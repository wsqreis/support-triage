package dev.wsqreis.triage.contracts;

/** Who classified the ticket: the LLM, or the rule-based fallback when the LLM was unavailable. */
public enum TriageSource {
    LLM,
    RULES
}
