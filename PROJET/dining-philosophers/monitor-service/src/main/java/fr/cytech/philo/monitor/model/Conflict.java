package fr.cytech.philo.monitor.model;

import java.time.Instant;

/**
 * Deux philosophes observés en train de manger simultanément avec une fourchette commune :
 * violation de l'exclusion mutuelle (sauf en partie 1, où rien n'est partagé).
 */
public record Conflict(long sequence, int philosopher, int other, int sharedFork, Instant detectedAt) {
}
