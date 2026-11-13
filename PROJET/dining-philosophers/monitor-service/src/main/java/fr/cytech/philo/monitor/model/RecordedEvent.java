package fr.cytech.philo.monitor.model;

import fr.cytech.philo.common.PhilosopherEvent;

import java.time.Instant;

/**
 * Événement tel que reçu par le moniteur.
 *
 * @param sequence   numéro d'arrivée au moniteur (ordre de réception, pas ordre causal)
 * @param event      l'événement envoyé par le philosophe
 * @param receivedAt heure de réception
 */
public record RecordedEvent(long sequence, PhilosopherEvent event, Instant receivedAt) {
}
