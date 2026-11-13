package fr.cytech.philo.supervisor.fork;

import fr.cytech.philo.common.ForkTopology;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Partie 3.2 — même algorithme que {@link UnsafePairStrategy}, mais la séquence
 * "vérifier F_i et F_{i+1} → réserver les deux ou refuser" est une section critique
 * protégée par un verrou unique.
 * <p>
 * Propriétés :
 * <ul>
 *   <li>exclusion mutuelle : une fourchette n'a jamais deux propriétaires ;</li>
 *   <li>pas d'interblocage : on n'attend jamais en détenant une fourchette (tout ou rien) ;</li>
 *   <li>libération sûre : seules les fourchettes réellement détenues sont libérées.</li>
 * </ul>
 * Limite : la famine reste possible (deux voisins peuvent "doubler" indéfiniment le philosophe
 * situé entre eux). Le verrou est équitable vis-à-vis des threads, pas vis-à-vis des philosophes.
 * Le verrou global sérialise aussi toutes les demandes : un verrou par fourchette, pris dans un
 * ordre fixe, serait plus fin — piste d'amélioration à discuter dans le rapport.
 */
public class LockedPairStrategy implements PairStrategy {

    private final ForkTable table;
    private final RaceWindow raceWindow;
    private final ReentrantLock lock = new ReentrantLock(true);

    public LockedPairStrategy(ForkTable table, RaceWindow raceWindow) {
        this.table = table;
        this.raceWindow = raceWindow;
    }

    @Override
    public boolean tryAcquire(int philosopher) {
        int left = ForkTopology.leftFork(philosopher, table.size());
        int right = ForkTopology.rightFork(philosopher, table.size());

        lock.lock();
        try {
            if (!table.isFree(left) || !table.isFree(right)) {
                return false;
            }
            raceWindow.pause(); // même pause que la version non sûre : elle ne change plus rien
            table.assign(left, philosopher);
            table.assign(right, philosopher);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(int philosopher) {
        int left = ForkTopology.leftFork(philosopher, table.size());
        int right = ForkTopology.rightFork(philosopher, table.size());

        lock.lock();
        try {
            freeIfOwned(left, philosopher);
            freeIfOwned(right, philosopher);
        } finally {
            lock.unlock();
        }
    }

    private void freeIfOwned(int fork, int philosopher) {
        Integer owner = table.ownerOf(fork);
        if (owner != null && owner == philosopher) {
            table.free(fork);
        }
    }

    @Override
    public String name() {
        return "LOCKED";
    }
}
