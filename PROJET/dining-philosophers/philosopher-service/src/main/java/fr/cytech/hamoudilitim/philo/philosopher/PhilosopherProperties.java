package fr.cytech.hamoudilitim.philo.philosopher;

import fr.cytech.hamoudilitim.philo.philosopher.core.ForkAssignment;
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
 * @param peers         URL de chaque philosophe, dans l'ordre des identifiants, y compris soi-même
 *                      (protocole à jetons et Master–Worker)
 * @param assignment    règle d'attribution des deux fourchettes d'un repas (modes TOKEN et ZOOKEEPER)
 * @param token         paramètres du protocole à jetons (mode TOKEN)
 * @param zookeeper     paramètres du verrou distribué (mode ZOOKEEPER)
 * @param graph         paramètres du calcul Master–Worker (partie 5)
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
        @DefaultValue List<String> peers,
        @DefaultValue("RANDOM") ForkAssignment.Rule assignment,
        @DefaultValue Token token,
        @DefaultValue ZooKeeper zookeeper,
        @DefaultValue Graph graph) {

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
        if (!peers.isEmpty() && peers.size() != count) {
            throw new IllegalArgumentException("philo.peers doit lister " + count
                    + " adresses (une par philosophe), reçu " + peers.size());
        }
    }

    /**
     * Partie 3.3.
     *
     * @param retransmitInterval délai sans réponse avant de réémettre sa demande
     * @param retryDelay         délai avant de réémettre un jeton non acquitté
     * @param giveUpAfter        partie 4 : un affamé qui attend plus longtemps abandonne sa demande, rend ses
     *                           jetons et redemande plus tard (limite l'effet de la panne d'un détenteur). 0 = jamais
     * @param dropRate           injection de pannes : probabilité de perdre un message ou son accusé (0 = off)
     * @param maxDelay           injection de pannes : latence réseau aléatoire maximale (0 = off)
     */
    public record Token(
            @DefaultValue("1s") Duration retransmitInterval,
            @DefaultValue("200ms") Duration retryDelay,
            @DefaultValue("10s") Duration giveUpAfter,
            @DefaultValue("0") double dropRate,
            @DefaultValue("0ms") Duration maxDelay) {
    }

    /**
     * Partie 3.4.
     *
     * @param connect        adresse du serveur ZooKeeper
     * @param root           nœud racine des verrous : un verrou par fourchette sous {@code root/forks/F<i>}
     * @param sessionTimeout délai après lequel ZooKeeper considère un philosophe muet comme mort et libère ses verrous
     */
    public record ZooKeeper(
            @DefaultValue("localhost:2181") String connect,
            @DefaultValue("/philo") String root,
            @DefaultValue("5s") Duration sessionTimeout) {
    }

    /**
     * Partie 5.
     *
     * @param taskTimeout délai au-delà duquel le Master relance une tâche sur un autre Worker
     * @param workerDelay simulation d'un Worker lent : pause avant chaque réponse (0 = off)
     */
    public record Graph(
            @DefaultValue("5s") Duration taskTimeout,
            @DefaultValue("0ms") Duration workerDelay) {
    }

    public enum Mode {
        /** Partie 1 : aucune ressource partagée. */
        SPOILED,
        /** Partie 2 : demande fourchette par fourchette au superviseur naïf. */
        NAIVE_SUPERVISOR,
        /** Parties 3.1 / 3.2 : demande des deux fourchettes en une requête. */
        PAIR_SUPERVISOR,
        /** Partie 3.3 : protocole distribué à jetons, sans superviseur. */
        TOKEN,
        /** Partie 3.4 : verrou distribué ZooKeeper (Apache Curator). */
        ZOOKEEPER
    }
}
