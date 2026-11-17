package fr.cytech.hamoudilitim.philo.philosopher.token;

import java.util.Comparator;
import java.util.List;

/**
 * Partie 3.3 — les trois messages du protocole à jetons, échangés en JSON entre philosophes.
 */
public final class TokenMessages {

    private TokenMessages() {
    }

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

    /**
     * Transmission du jeton d'une fourchette (= droit exclusif de l'utiliser).
     *
     * @param fork     fourchette concernée
     * @param seq      numéro de transfert, strictement croissant le long du trajet du jeton. Le destinataire
     *                 ignore tout jeton dont le numéro n'est pas supérieur au dernier vu : un message réémis
     *                 (accusé de réception perdu) ne peut donc jamais créer un second jeton
     * @param from     émetteur
     * @param to       destinataire
     * @param lamport  horodatage logique de l'envoi
     * @param released pour chaque philosophe, le dernier repas que l'émetteur sait terminé. Propagé avec le
     *                 jeton pour purger les demandes périmées même si un message de libération a été perdu
     */
    public record TokenTransfer(int fork, long seq, int from, int to, long lamport, List<Long> released) {

        public TokenTransfer {
            released = released == null ? List.of() : List.copyOf(released);
        }
    }

    /**
     * Annonce qu'une demande est terminée (repas fini ou demande abandonnée) :
     * les autres la retirent de leur file d'attente.
     */
    public record TokenRelease(int requester, long meal, long lamport) {
    }
}
