package dev.wsqreis.triage.triage.classify;

public interface TicketClassifier {

    Classification classify(String subject, String body);
}
