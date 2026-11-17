package fr.cytech.hamoudilitim.philo.supervisor.fork;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * État partagé des fourchettes : propriétaire de chaque fourchette (null = libre).
 * <p>
 * Chaque case est lue/écrite de façon atomique, mais une suite d'opérations
 * (vérifier puis réserver) ne l'est PAS : c'est aux stratégies de le garantir.
 * <p>
 * {@link #assign} compte une "violation" quand on écrase le propriétaire d'une fourchette
 * déjà prise par quelqu'un d'autre : c'est la preuve qu'une même fourchette a été attribuée
 * à deux philosophes. Ce compteur sert d'oracle dans les tests.
 */
public class ForkTable {

    private final AtomicReferenceArray<Integer> owners;
    private final AtomicLong violations = new AtomicLong();

    public ForkTable(int size) {
        if (size < 2) {
            throw new IllegalArgumentException("il faut au moins 2 fourchettes");
        }
        this.owners = new AtomicReferenceArray<>(size);
    }

    public int size() {
        return owners.length();
    }

    public boolean isFree(int fork) {
        return owners.get(checkIndex(fork)) == null;
    }

    public Integer ownerOf(int fork) {
        return owners.get(checkIndex(fork));
    }

    /** Pose le propriétaire sans aucune vérification préalable. */
    public void assign(int fork, int philosopher) {
        Integer previous = owners.getAndSet(checkIndex(fork), philosopher);
        if (previous != null && previous != philosopher) {
            violations.incrementAndGet();
        }
    }

    public void free(int fork) {
        owners.set(checkIndex(fork), null);
    }

    public long violations() {
        return violations.get();
    }

    public List<Integer> snapshot() {
        List<Integer> copy = new ArrayList<>(owners.length());
        for (int i = 0; i < owners.length(); i++) {
            copy.add(owners.get(i));
        }
        return copy;
    }

    public void reset() {
        for (int i = 0; i < owners.length(); i++) {
            owners.set(i, null);
        }
        violations.set(0);
    }

    public int checkIndex(int fork) {
        if (fork < 0 || fork >= owners.length()) {
            throw new IllegalArgumentException("fourchette " + fork + " hors de [0, " + (owners.length() - 1) + "]");
        }
        return fork;
    }
}
