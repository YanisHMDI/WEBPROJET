package fr.cytech.philo.philosopher.token;

import java.util.Comparator;
import java.util.List;

/**
 * Demande diffusée par un philosophe affamé à tous les autres.
 *
 * @param requester philosophe demandeur
 * @param meal      numéro du repas demandé (1, 2, 3…) : identifie la demande de façon unique avec
 *                  {@code requester} et permet d'ignorer doublons et demandes périmées
 * @param lamport   horodatage logique de la demande : fixe sa PRIORITÉ (plus petit = plus prioritaire)
 * @param forks     les deux fourchettes nécessaires, choisies par la règle d'attribution
 */
public record TokenRequest(int requester, long meal, long lamport, List<Integer> forks) {

    /** Ordre total de Lamport : (horodatage, identifiant). Le plus petit est servi en premier. */
    public static final Comparator<TokenRequest> PRIORITY = Comparator
            .comparingLong(TokenRequest::lamport)
            .thenComparingInt(TokenRequest::requester);

    public TokenRequest {
        if (requester < 0 || meal < 1 || lamport < 0) {
            throw new IllegalArgumentException("demande invalide");
        }
        if (forks == null || forks.size() != 2 || forks.get(0).equals(forks.get(1))) {
            throw new IllegalArgumentException("une demande porte sur exactement deux fourchettes distinctes");
        }
        forks = List.copyOf(forks);
    }

    public boolean needs(int fork) {
        return forks.contains(fork);
    }

    public String key() {
        return "P" + requester + "#" + meal;
    }
}
