package fr.cytech.hamoudilitim.philo.supervisor.fork;

/**
 * Partie 2 — superviseur naïf, fourchette par fourchette.
 * <p>
 * Conformément à l'énoncé, il ne fait AUCUN contrôle global :
 * <ul>
 *   <li>il ne sait pas quel philosophe a besoin de quelles fourchettes ;</li>
 *   <li>il ne vérifie pas qu'un philosophe a déjà l'autre fourchette ;</li>
 *   <li>il accepte toute restitution, même par quelqu'un qui ne possède pas la fourchette.</li>
 * </ul>
 * Failles volontairement présentes (mises en évidence par NaiveForkServiceTest) :
 * <ol>
 *   <li>interblocage : chacun prend sa fourchette gauche puis attend la droite pour toujours ;</li>
 *   <li>course "check-then-act" : deux philosophes voient la fourchette libre et l'obtiennent tous les deux ;</li>
 *   <li>restitution par un non-propriétaire : un philosophe peut libérer la fourchette d'un autre ;</li>
 *   <li>pas de famine évitée : rien ne garantit qu'un philosophe finira par être servi.</li>
 * </ol>
 */
public class NaiveForkService {

    private final ForkTable table;
    private final RaceWindow raceWindow;

    public NaiveForkService(ForkTable table, RaceWindow raceWindow) {
        this.table = table;
        this.raceWindow = raceWindow;
    }

    public boolean isAvailable(int fork) {
        return table.isFree(fork);
    }

    /** Vérifie puis attribue — deux étapes non atomiques. */
    public boolean acquire(int fork, int philosopher) {
        if (!table.isFree(fork)) {
            return false;
        }
        raceWindow.pause();
        table.assign(fork, philosopher);
        return true;
    }

    /** Accepte la libération sans vérifier le propriétaire. */
    public void release(int fork, int philosopher) {
        table.free(fork);
    }
}
