package fr.cytech.hamoudilitim.philo.common;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Événement horodaté envoyé par un philosophe au moniteur.
 *
 * @param philosopherId identifiant du philosophe (0..N-1)
 * @param type          nature de l'événement
 * @param lamport       valeur de l'horloge logique locale au moment de l'émission
 * @param timestamp     heure physique d'émission (indicative uniquement, ne sert pas à ordonner)
 * @param forks         fourchettes utilisées pour ce repas (renseigné sur EATING). Vide = disposition
 *                      classique F_i / F_{i+1}, que le moniteur déduit alors lui-même.
 */
public record PhilosopherEvent(int philosopherId, EventType type, long lamport, Instant timestamp,
                               List<Integer> forks) {

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
        forks = forks == null ? List.of() : List.copyOf(forks);
    }

    public static PhilosopherEvent of(int philosopherId, EventType type, long lamport) {
        return of(philosopherId, type, lamport, List.of());
    }

    public static PhilosopherEvent of(int philosopherId, EventType type, long lamport, List<Integer> forks) {
        return new PhilosopherEvent(philosopherId, type, lamport, Instant.now(), forks);
    }
}
