package fr.cytech.philo.supervisor.fork;

/**
 * Parties 3.1 / 3.2 — le philosophe demande ses deux fourchettes (F_i et F_{i+1}) en une seule requête.
 * Le superviseur répond "les deux" ou "aucune" : il n'y a donc plus de détention partielle,
 * ce qui supprime l'attente circulaire, à condition que l'opération soit atomique.
 */
public interface PairStrategy {

    /** @return true si les deux fourchettes ont été réservées pour ce philosophe */
    boolean tryAcquire(int philosopher);

    void release(int philosopher);

    String name();
}
