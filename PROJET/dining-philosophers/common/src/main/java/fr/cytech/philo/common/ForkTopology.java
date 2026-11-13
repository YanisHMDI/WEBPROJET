package fr.cytech.philo.common;

/**
 * Disposition classique autour d'une table ronde : le philosophe i utilise
 * la fourchette F_i (gauche) et F_{(i+1) mod N} (droite).
 */
public final class ForkTopology {

    private ForkTopology() {
    }

    public static int leftFork(int philosopherId, int count) {
        check(philosopherId, count);
        return philosopherId;
    }

    public static int rightFork(int philosopherId, int count) {
        check(philosopherId, count);
        return (philosopherId + 1) % count;
    }

    public static int leftNeighbour(int philosopherId, int count) {
        check(philosopherId, count);
        return (philosopherId - 1 + count) % count;
    }

    public static int rightNeighbour(int philosopherId, int count) {
        check(philosopherId, count);
        return (philosopherId + 1) % count;
    }

    private static void check(int philosopherId, int count) {
        if (count < 2) {
            throw new IllegalArgumentException("il faut au moins 2 philosophes, reçu " + count);
        }
        if (philosopherId < 0 || philosopherId >= count) {
            throw new IllegalArgumentException("philosophe " + philosopherId + " hors de [0, " + (count - 1) + "]");
        }
    }
}
