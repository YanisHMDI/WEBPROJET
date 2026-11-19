package fr.cytech.hamoudilitim.philo.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * @param philosophers             nombre de philosophes autour de la table
 * @param detectNeighbourConflicts signaler deux philosophes qui mangent en même temps avec une fourchette
 *                                 commune (normal en partie 1, anormal dès qu'il y a des fourchettes partagées).
 *                                 Les fourchettes sont celles annoncées dans l'événement EATING, ou à défaut
 *                                 F_i / F_{i+1}.
 * @param maxEvents                nombre maximal d'événements conservés en mémoire
 * @param kafka                    partie 6 : lire aussi les événements dans le topic Kafka
 * @param philosopherUrls          adresses des philosophes, pour le panneau de contrôle du tableau de bord
 */
@ConfigurationProperties(prefix = "monitor")
public record MonitorProperties(
        @DefaultValue("5") int philosophers,
        @DefaultValue("true") boolean detectNeighbourConflicts,
        @DefaultValue("10000") int maxEvents,
        @DefaultValue Kafka kafka,
        @DefaultValue List<String> philosopherUrls) {

    public record Kafka(@DefaultValue("false") boolean enabled) {
    }

    /** Pour les tests : sans Kafka ni panneau de contrôle. */
    public static MonitorProperties of(int philosophers, boolean detectConflicts, int maxEvents) {
        return new MonitorProperties(philosophers, detectConflicts, maxEvents, new Kafka(false), List.of());
    }
}
