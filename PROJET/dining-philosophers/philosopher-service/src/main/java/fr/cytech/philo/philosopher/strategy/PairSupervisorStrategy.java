package fr.cytech.philo.philosopher.strategy;

import fr.cytech.philo.philosopher.core.DiningStrategy;
import fr.cytech.philo.philosopher.core.Sleeper;

import java.time.Duration;

/**
 * Parties 3.1 / 3.2 — le philosophe demande ses deux fourchettes en une seule requête
 * et réessaie tant qu'on les lui refuse. La correction dépend entièrement du superviseur
 * (UNSAFE ou LOCKED).
 */
public class PairSupervisorStrategy implements DiningStrategy {

    private final int id;
    private final SupervisorClient supervisor;
    private final Sleeper sleeper;
    private final Duration retryDelay;

    public PairSupervisorStrategy(int id, SupervisorClient supervisor, Sleeper sleeper, Duration retryDelay) {
        this.id = id;
        this.supervisor = supervisor;
        this.sleeper = sleeper;
        this.retryDelay = retryDelay;
    }

    @Override
    public void acquireForks() throws InterruptedException {
        while (!supervisor.acquirePair(id)) {
            sleeper.sleep(retryDelay);
        }
    }

    @Override
    public void releaseForks() {
        supervisor.releasePair(id);
    }
}
