package fr.cytech.philo.common;

import java.time.Instant;
import java.util.Objects;

/**
 * Événement horodaté envoyé par un philosophe au moniteur.
 *
 * @param philosopherId identifiant du philosophe (0..N-1)
 * @param type          nature de l'événement
 * @param lamport       valeur de l'horloge logique locale au moment de l'émission
 * @param timestamp     heure physique d'émission (indicative uniquement, ne sert pas à ordonner)
 */
public record PhilosopherEvent(int philosopherId, EventType type, long lamport, Instant timestamp) {

    public PhilosopherEvent {
        Objects.requireNonNull(type, "type");
        if (philosopherId < 0) {
            throw new IllegalArgumentException("philosopherId doit être >= 0");
        }
        if (lamport < 0) {
            throw new IllegalArgumentException("lamport doit être >= 0");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    public static PhilosopherEvent of(int philosopherId, EventType type, long lamport) {
        return new PhilosopherEvent(philosopherId, type, lamport, Instant.now());
    }
}
