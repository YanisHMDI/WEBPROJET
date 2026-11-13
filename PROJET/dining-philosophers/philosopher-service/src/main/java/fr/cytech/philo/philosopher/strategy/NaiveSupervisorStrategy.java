package fr.cytech.philo.philosopher.strategy;

import fr.cytech.philo.common.ForkTopology;
import fr.cytech.philo.philosopher.client.SupervisorClient;
import fr.cytech.philo.philosopher.core.DiningStrategy;
import fr.cytech.philo.philosopher.core.Sleeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Partie 2 — demande naïve, une fourchette après l'autre :
 * <ol>
 *   <li>"la fourchette gauche est-elle libre ?" → si oui, la demander ;</li>
 *   <li>puis pareil pour la droite, EN GARDANT la gauche pendant l'attente.</li>
 * </ol>
 * Garder une ressource en en attendant une autre = condition d'attente circulaire → interblocage possible.
 */
public class NaiveSupervisorStrategy implements DiningStrategy {

    private static final Logger log = LoggerFactory.getLogger(NaiveSupervisorStrategy.class);

    private final int id;
    private final int left;
    private final int right;
    private final SupervisorClient supervisor;
    private final Sleeper sleeper;
    private final Duration retryDelay;

    public NaiveSupervisorStrategy(int id, int count, SupervisorClient supervisor, Sleeper sleeper, Duration retryDelay) {
        this.id = id;
        this.left = ForkTopology.leftFork(id, count);
        this.right = ForkTopology.rightFork(id, count);
        this.supervisor = supervisor;
        this.sleeper = sleeper;
        this.retryDelay = retryDelay;
    }

    @Override
    public void acquireForks() throws InterruptedException {
        take(left);
        log.debug("P{} tient F{} et attend F{}", id, left, right);
        take(right);
    }

    private void take(int fork) throws InterruptedException {
        long attempts = 0;
        while (true) {
            if (supervisor.isAvailable(fork) && supervisor.acquireFork(fork, id)) {
                return;
            }
            if (++attempts % 50 == 0) {
                log.warn("P{} attend F{} depuis {} tentatives (interblocage ?)", id, fork, attempts);
            }
            sleeper.sleep(retryDelay);
        }
    }

    @Override
    public void releaseForks() {
        supervisor.releaseFork(left, id);
        supervisor.releaseFork(right, id);
    }
}
