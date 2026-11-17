package fr.cytech.hamoudilitim.philo.monitor;

import fr.cytech.hamoudilitim.philo.common.EventType;
import fr.cytech.hamoudilitim.philo.common.PhilosopherEvent;

import java.time.Instant;
import java.util.List;

/**
 * Données manipulées par le moniteur et renvoyées en JSON par son API.
 */
public final class MonitorModel {

    private MonitorModel() {
    }

    public enum PhilosopherState {
        UNKNOWN,
        THINKING,
        HUNGRY,
        EATING;

        public static PhilosopherState after(EventType type) {
            return switch (type) {
                case HUNGRY -> HUNGRY;
                case EATING -> EATING;
                case DONE -> THINKING;
            };
        }
    }

    /**
     * Vue courante d'un philosophe, reconstruite à partir de ses événements.
     *
     * @param forks fourchettes tenues pendant le repas en cours (vide s'il ne mange pas)
     */
    public record PhilosopherView(int id, PhilosopherState state, int meals, long lastLamport, Instant lastSeen,
                                  List<Integer> forks) {
    }

    /**
     * Événement tel que reçu par le moniteur.
     *
     * @param sequence   numéro d'arrivée au moniteur (ordre de réception, pas ordre causal)
     * @param event      l'événement envoyé par le philosophe
     * @param receivedAt heure de réception
     */
    public record RecordedEvent(long sequence, PhilosopherEvent event, Instant receivedAt) {
    }

    /**
     * Deux philosophes observés en train de manger simultanément avec une fourchette commune :
     * violation de l'exclusion mutuelle (sauf en partie 1, où rien n'est partagé).
     */
    public record Conflict(long sequence, int philosopher, int other, int sharedFork, Instant detectedAt) {
    }

    public record DashboardSnapshot(
            int philosopherCount,
            long totalEvents,
            long staleEvents,
            List<PhilosopherView> philosophers,
            List<Conflict> conflicts) {
    }
}
