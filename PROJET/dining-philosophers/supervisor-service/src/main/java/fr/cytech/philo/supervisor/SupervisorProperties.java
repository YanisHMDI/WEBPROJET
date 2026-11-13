package fr.cytech.philo.supervisor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param forks      nombre de fourchettes (= nombre de philosophes)
 * @param pairMode   stratégie utilisée par /api/pairs : UNSAFE (3.1) ou LOCKED (3.2)
 * @param raceWindow pause artificielle entre "vérifier" et "réserver" : élargit la fenêtre
 *                   de concurrence pour rendre les bugs des versions naïves reproductibles
 */
@ConfigurationProperties(prefix = "supervisor")
public record SupervisorProperties(
        @DefaultValue("5") int forks,
        @DefaultValue("LOCKED") PairMode pairMode,
        @DefaultValue("0ms") Duration raceWindow) {

    public enum PairMode {
        /** Partie 3.1 : vérification puis réservation, sans verrou. */
        UNSAFE,
        /** Partie 3.2 : vérification + réservation dans une section critique. */
        LOCKED
    }
}
