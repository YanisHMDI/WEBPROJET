package fr.cytech.philo.monitor.model;

import java.time.Instant;
import java.util.List;

/**
 * Vue courante d'un philosophe, reconstruite à partir de ses événements.
 *
 * @param forks fourchettes tenues pendant le repas en cours (vide s'il ne mange pas)
 */
public record PhilosopherView(int id, PhilosopherState state, int meals, long lastLamport, Instant lastSeen,
                              List<Integer> forks) {
}
