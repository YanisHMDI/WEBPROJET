package fr.cytech.philo.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param philosophers           nombre de philosophes autour de la table
 * @param detectNeighbourConflicts signaler deux voisins qui mangent en même temps
 *                               (normal en partie 1, anormal dès qu'il y a des fourchettes partagées)
 * @param maxEvents              nombre maximal d'événements conservés en mémoire
 */
@ConfigurationProperties(prefix = "monitor")
public record MonitorProperties(
        @DefaultValue("5") int philosophers,
        @DefaultValue("true") boolean detectNeighbourConflicts,
        @DefaultValue("10000") int maxEvents) {
}
