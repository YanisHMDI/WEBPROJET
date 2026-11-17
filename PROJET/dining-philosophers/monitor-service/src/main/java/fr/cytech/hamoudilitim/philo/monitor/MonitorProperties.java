package fr.cytech.hamoudilitim.philo.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param philosophers             nombre de philosophes autour de la table
 * @param detectNeighbourConflicts signaler deux philosophes qui mangent en même temps avec une fourchette
 *                                 commune (normal en partie 1, anormal dès qu'il y a des fourchettes partagées).
 *                                 Les fourchettes sont celles annoncées dans l'événement EATING, ou à défaut
 *                                 F_i / F_{i+1}.
 * @param maxEvents                nombre maximal d'événements conservés en mémoire
 */
@ConfigurationProperties(prefix = "monitor")
public record MonitorProperties(
        @DefaultValue("5") int philosophers,
        @DefaultValue("true") boolean detectNeighbourConflicts,
        @DefaultValue("10000") int maxEvents) {
}
