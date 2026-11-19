package fr.cytech.hamoudilitim.philo.common;

/**
 * Partie 6 — noms des topics Kafka partagés par les philosophes (producteurs) et le moniteur (consommateur).
 */
public final class EventTopics {

    /**
     * Événements HUNGRY / EATING / DONE. La clé du message est l'identifiant du philosophe : tous les
     * événements d'un même philosophe vont dans la même partition, donc Kafka conserve leur ordre.
     */
    public static final String EVENTS = "philo.events";

    private EventTopics() {
    }
}
