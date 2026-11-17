package fr.cytech.philo.philosopher.token;

/**
 * Annonce qu'une demande est terminée (repas fini ou demande abandonnée) :
 * les autres la retirent de leur file d'attente.
 */
public record TokenRelease(int requester, long meal, long lamport) {
}
