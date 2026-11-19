package fr.cytech.hamoudilitim.philo.monitor;

import fr.cytech.hamoudilitim.philo.common.EventTopics;
import fr.cytech.hamoudilitim.philo.common.EventType;
import fr.cytech.hamoudilitim.philo.common.PhilosopherEvent;
import fr.cytech.hamoudilitim.philo.monitor.MonitorModel.PhilosopherState;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partie 6 — le moniteur lit les événements dans un vrai broker Kafka, démarré dans la JVM du test.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "monitor.kafka.enabled=true",
        "monitor.philosopher-urls="
})
@EmbeddedKafka(kraft = true, partitions = 1, topics = EventTopics.EVENTS)
@DirtiesContext
class KafkaEventListenerTest {

    @Autowired
    private EventStore store;
    @Autowired
    private EmbeddedKafkaBroker broker;

    private KafkaTemplate<String, Object> producer() {
        Map<String, Object> props = KafkaTestUtils.producerProps(broker);
        var factory = new DefaultKafkaProducerFactory<String, Object>(props, new StringSerializer(), new JsonSerializer<>());
        return new KafkaTemplate<>(factory);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).as("condition atteinte avant 15 s").isTrue();
    }

    @Test
    void eventsPublishedInKafkaReachTheDashboard() throws Exception {
        KafkaTemplate<String, Object> kafka = producer();
        kafka.send(EventTopics.EVENTS, "2", PhilosopherEvent.of(2, EventType.HUNGRY, 1));
        kafka.send(EventTopics.EVENTS, "2", PhilosopherEvent.of(2, EventType.EATING, 2, List.of(2, 3)));

        await(() -> store.snapshot().philosophers().get(2).state() == PhilosopherState.EATING);
        assertThat(store.snapshot().philosophers().get(2).forks()).containsExactly(2, 3);
    }

    @Test
    void anUnreadableMessageDoesNotBlockTheFollowingOnes() throws Exception {
        KafkaTemplate<String, Object> kafka = producer();
        kafka.send(EventTopics.EVENTS, "4", "ceci n'est pas un événement");
        kafka.send(EventTopics.EVENTS, "4", PhilosopherEvent.of(4, EventType.HUNGRY, 50));

        await(() -> store.snapshot().philosophers().get(4).state() == PhilosopherState.HUNGRY);
    }
}
