package fr.cytech.philo.supervisor.fork;

import fr.cytech.philo.common.ForkTopology;

/**
 * Partie 3.1 — "vérifier les deux, puis réserver les deux", SANS verrou.
 * <p>
 * Chaque requête HTTP est servie par un thread différent de Tomcat. Deux voisins P_i et P_{i+1}
 * peuvent tous deux lire F_{i+1} libre avant que l'un des deux ne l'ait réservée :
 * ils mangent alors avec la même fourchette. C'est une condition de course.
 */
public class UnsafePairStrategy implements PairStrategy {

    private final ForkTable table;
    private final RaceWindow raceWindow;

    public UnsafePairStrategy(ForkTable table, RaceWindow raceWindow) {
        this.table = table;
        this.raceWindow = raceWindow;
    }

    @Override
    public boolean tryAcquire(int philosopher) {
        int left = ForkTopology.leftFork(philosopher, table.size());
        int right = ForkTopology.rightFork(philosopher, table.size());

        if (!table.isFree(left) || !table.isFree(right)) {   // vérifier
            return false;
        }
        raceWindow.pause();                                   // ← un autre thread peut passer ici
        table.assign(left, philosopher);                      // réserver
        table.assign(right, philosopher);
        return true;
    }

    @Override
    public void release(int philosopher) {
        table.free(ForkTopology.leftFork(philosopher, table.size()));
        table.free(ForkTopology.rightFork(philosopher, table.size()));
    }

    @Override
    public String name() {
        return "UNSAFE";
    }
}
