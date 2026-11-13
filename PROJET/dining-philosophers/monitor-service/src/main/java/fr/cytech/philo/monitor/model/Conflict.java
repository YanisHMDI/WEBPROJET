package fr.cytech.philo.monitor.model;

import java.time.Instant;

/**
 * Deux voisins observés en train de manger simultanément : ils partagent une fourchette,
 * c'est donc une violation de l'exclusion mutuelle (sauf en partie 1, où rien n'est partagé).
 */
public record Conflict(long sequence, int philosopher, int neighbour, int sharedFork, Instant detectedAt) {
}
