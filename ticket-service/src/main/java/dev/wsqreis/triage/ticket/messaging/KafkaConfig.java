package dev.wsqreis.triage.ticket.messaging;

import dev.wsqreis.triage.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

import java.time.Clock;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic ticketCreatedTopic() {
        return TopicBuilder.name(Topics.TICKET_CREATED).partitions(3).build();
    }

    @Bean
    NewTopic ticketTriagedTopic() {
        return TopicBuilder.name(Topics.TICKET_TRIAGED).partitions(3).build();
    }

    @Bean
    NewTopic ticketTriagedDeadLetterTopic() {
        return TopicBuilder.name(Topics.TICKET_TRIAGED + "-dlt").partitions(3).build();
    }

    /**
     * Three retries a second apart, then the message goes to the dead letter topic instead of blocking the partition.
     * Malformed JSON will never parse, so it skips the retries.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(1000L, 3));
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
