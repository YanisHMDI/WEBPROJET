package fr.cytech.hamoudilitim.philo.philosopher.core;

import fr.cytech.hamoudilitim.philo.common.EventTopics;
import fr.cytech.hamoudilitim.philo.common.EventType;
import fr.cytech.hamoudilitim.philo.common.PhilosopherEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Partie 6 — envoi des événements par Kafka. */
@ExtendWith(MockitoExtension.class)
class KafkaEventReporterTest {

    @Mock
    private KafkaTemplate<String, PhilosopherEvent> kafka;

    @Test
    void eventIsPublishedWithThePhilosopherIdAsKey() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<SendResult<String, PhilosopherEvent>>());
        PhilosopherEvent event = PhilosopherEvent.of(3, EventType.EATING, 7);

        new KafkaEventReporter(kafka).report(event);

        // même clé = même partition = ordre conservé pour ce philosophe
        verify(kafka).send(EventTopics.EVENTS, "3", event);
    }

    @Test
    void kafkaFailureNeverStopsThePhilosopher() {
        when(kafka.send(anyString(), anyString(), any())).thenThrow(new IllegalStateException("broker injoignable"));

        assertThatCode(() -> new KafkaEventReporter(kafka).report(PhilosopherEvent.of(0, EventType.HUNGRY, 1)))
                .doesNotThrowAnyException();
    }

    @Test
    void asynchronousFailureIsOnlyLogged() {
        CompletableFuture<SendResult<String, PhilosopherEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("délai dépassé"));
        when(kafka.send(anyString(), anyString(), any())).thenReturn(failed);

        assertThatCode(() -> new KafkaEventReporter(kafka).report(PhilosopherEvent.of(0, EventType.DONE, 2)))
                .doesNotThrowAnyException();
    }
}
