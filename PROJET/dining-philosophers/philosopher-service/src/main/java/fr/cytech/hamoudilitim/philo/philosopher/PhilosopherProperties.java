package fr.cytech.hamoudilitim.philo.philosopher;

import fr.cytech.hamoudilitim.philo.philosopher.token.TokenStrategy.ForkAssignment;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Configuration d'un philosophe (une instance = un microservice).
 *
 * @param id            identifiant 0..count-1
 * @param count         nombre total de philosophes
 * @param mode          façon d'obtenir les fourchettes
 * @param thinkMin      durée minimale de réflexion
 * @param thinkMax      durée maximale de réflexion
 * @param eatMin        durée minimale de repas
 * @param eatMax        durée maximale de repas
 * @param retryDelay    attente entre deux tentatives quand une fourchette est refusée
 * @param httpTimeout   délai max d'une requête HTTP sortante
 * @param autoStart     démarrer la boucle dès que l'application est prête
 * @param monitorUrl    URL du monitor-service
 * @param supervisorUrl URL du supervisor-service (modes NAIVE_SUPERVISOR et PAIR_SUPERVISOR)
 * @param token         paramètres du protocole à jetons (mode TOKEN)
 */
@ConfigurationProperties(prefix = "philo")
public record PhilosopherProperties(
        @DefaultValue("0") int id,
        @DefaultValue("5") int count,
        @DefaultValue("SPOILED") Mode mode,
        @DefaultValue("500ms") Duration thinkMin,
        @DefaultValue("2s") Duration thinkMax,
        @DefaultValue("500ms") Duration eatMin,
        @DefaultValue("2s") Duration eatMax,
        @DefaultValue("100ms") Duration retryDelay,
        @DefaultValue("2s") Duration httpTimeout,
        @DefaultValue("true") boolean autoStart,
        @DefaultValue("http://localhost:8080") String monitorUrl,
        @DefaultValue("http://localhost:8081") String supervisorUrl,
        @DefaultValue Token token) {

    public PhilosopherProperties {
        if (count < 2) {
            throw new IllegalArgumentException("philo.count doit être >= 2");
        }
        if (id < 0 || id >= count) {
            throw new IllegalArgumentException("philo.id doit être dans [0, " + (count - 1) + "]");
        }
        if (thinkMin.compareTo(thinkMax) > 0 || eatMin.compareTo(eatMax) > 0) {
            throw new IllegalArgumentException("durée min > durée max");
        }
    }

    /**
     * Partie 3.3.
     *
     * @param peers              URL de chaque philosophe, dans l'ordre des identifiants (y compris soi-même)
     * @param assignment         règle d'attribution des deux fourchettes d'un repas
     * @param retransmitInterval délai sans réponse avant de réémettre sa demande
     * @param retryDelay         délai avant de réémettre un jeton non acquitté
     * @param dropRate           injection de pannes : probabilité de perdre un message ou son accusé (0 = off)
     * @param maxDelay           injection de pannes : latence réseau aléatoire maximale (0 = off)
     */
    public record Token(
            @DefaultValue List<String> peers,
            @DefaultValue("RANDOM") ForkAssignment.Rule assignment,
            @DefaultValue("1s") Duration retransmitInterval,
            @DefaultValue("200ms") Duration retryDelay,
            @DefaultValue("0") double dropRate,
            @DefaultValue("0ms") Duration maxDelay) {
    }

    public enum Mode {
        /** Partie 1 : aucune ressource partagée. */
        SPOILED,
        /** Partie 2 : demande fourchette par fourchette au superviseur naïf. */
        NAIVE_SUPERVISOR,
        /** Parties 3.1 / 3.2 : demande des deux fourchettes en une requête. */
        PAIR_SUPERVISOR,
        /** Partie 3.3 : protocole distribué à jetons, sans superviseur. */
        TOKEN
        // Partie 3.4 : ajouter ZOOKEEPER (verrou distribué).
    }
}
