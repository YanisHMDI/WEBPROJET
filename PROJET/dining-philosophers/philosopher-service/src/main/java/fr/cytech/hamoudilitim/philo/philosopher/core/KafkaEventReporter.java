package fr.cytech.hamoudilitim.philo.philosopher.core;

import fr.cytech.hamoudilitim.philo.common.EventTopics;
import fr.cytech.hamoudilitim.philo.common.PhilosopherEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Partie 6 — envoie les événements au moniteur par Kafka (communication asynchrone).
 * <ul>
 *   <li>la clé est l'identifiant du philosophe : ses événements restent dans l'ordre (même partition) ;</li>
 *   <li>l'envoi n'attend pas le moniteur : le philosophe continue même si le moniteur est arrêté, et le
 *       moniteur rattrape les événements en attente à son redémarrage ;</li>
 *   <li>si Kafka lui-même est injoignable, l'événement est perdu et journalisé, sans bloquer le philosophe
 *       ({@code max.block.ms} court dans la configuration).</li>
 * </ul>
 */
public class KafkaEventReporter implements EventReporter {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventReporter.class);

    private final KafkaTemplate<String, PhilosopherEvent> kafka;

    public KafkaEventReporter(KafkaTemplate<String, PhilosopherEvent> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void report(PhilosopherEvent event) {
        log.info("P{} {} (h={})", event.philosopherId(), event.type(), event.lamport());
        try {
            kafka.send(EventTopics.EVENTS, String.valueOf(event.philosopherId()), event)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("Kafka : événement perdu ({})", error.getMessage());
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("Kafka injoignable, événement perdu : {}", e.getMessage());
        }
    }
}
