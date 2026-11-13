package fr.cytech.philo.philosopher.core;

import fr.cytech.philo.common.PhilosopherEvent;

/**
 * Envoie les événements au moniteur. Une panne du moniteur ne doit jamais bloquer
 * ni faire planter le philosophe : les implémentations avalent les erreurs.
 */
@FunctionalInterface
public interface EventReporter {

    void report(PhilosopherEvent event);
}
