package dev.wsqreis.triage.triage.messaging;

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
    NewTopic ticketCreatedDeadLetterTopic() {
        return TopicBuilder.name(Topics.TICKET_CREATED + "-dlt").partitions(3).build();
    }

    /** Three retries two seconds apart, then the dead letter topic. Malformed JSON skips the retries. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(2000L, 3));
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
