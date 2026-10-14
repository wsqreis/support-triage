package dev.wsqreis.triage.contracts;

/** Kafka topics. Payloads are JSON without type headers, so services stay decoupled from each other's classes. */
public final class Topics {

    public static final String TICKET_CREATED = "tickets.created";
    public static final String TICKET_TRIAGED = "tickets.triaged";

    private Topics() {
    }
}
