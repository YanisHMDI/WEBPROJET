package fr.cytech.philo.monitor.model;

import java.time.Instant;

/**
 * Vue courante d'un philosophe, reconstruite à partir de ses événements.
 */
public record PhilosopherView(int id, PhilosopherState state, int meals, long lastLamport, Instant lastSeen) {
}
