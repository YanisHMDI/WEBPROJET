package fr.cytech.philo.philosopher.token;

import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Règle d'attribution automatique des deux fourchettes d'un philosophe pour un repas.
 */
@FunctionalInterface
public interface ForkAssignment {

    List<Integer> forksFor(int philosopher, int count);

    enum Rule { NEIGHBOURS, RANDOM }

    static ForkAssignment of(Rule rule) {
        return switch (rule) {
            case NEIGHBOURS -> neighbours();
            case RANDOM -> random(new Random());
        };
    }

    /** Table ronde classique : F_i et F_{(i+1) mod N}. */
    static ForkAssignment neighbours() {
        return (p, n) -> List.of(p, (p + 1) % n);
    }

    /**
     * Règle par défaut (« fourchettes quelconques, sans contrainte de voisinage ») : la fourchette F_i
     * que le philosophe gère, plus une autre tirée uniformément parmi les N-1 restantes, à chaque repas.
     * Garder F_i assure que chaque fourchette est régulièrement réclamée par son « propriétaire ».
     */
    static ForkAssignment random(RandomGenerator rnd) {
        return (p, n) -> {
            int other = rnd.nextInt(n - 1);
            if (other >= p) {
                other++;
            }
            return List.of(p, other);
        };
    }
}
