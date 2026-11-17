package fr.cytech.philo.philosopher.token;

import java.util.List;

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
