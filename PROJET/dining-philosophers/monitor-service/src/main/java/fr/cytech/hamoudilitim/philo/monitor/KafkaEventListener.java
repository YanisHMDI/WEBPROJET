package fr.cytech.hamoudilitim.philo.monitor;

import fr.cytech.hamoudilitim.philo.common.EventTopics;
import fr.cytech.hamoudilitim.philo.common.PhilosopherEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Partie 6 — lit les événements publiés par les philosophes dans Kafka (en plus de l'API HTTP).
 * <p>
 * Avantage par rapport à HTTP : si le moniteur redémarre, il reprend là où il s'était arrêté (offsets du
 * groupe {@code monitor}) au lieu de perdre les événements émis pendant son absence.
 */
@Component
@ConditionalOnProperty(name = "monitor.kafka.enabled", havingValue = "true")
public class KafkaEventListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventListener.class);

    private final EventStore store;

    public KafkaEventListener(EventStore store) {
        this.store = store;
    }

    @KafkaListener(topics = EventTopics.EVENTS)
    public void onEvent(PhilosopherEvent event) {
        if (event == null) {
            return;                                   // message illisible, écarté par ErrorHandlingDeserializer
        }
        try {
            store.record(event);
        } catch (IllegalArgumentException e) {
            log.warn("événement Kafka ignoré : {}", e.getMessage());
        }
    }
}
