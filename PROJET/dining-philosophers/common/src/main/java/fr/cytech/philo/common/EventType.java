package fr.cytech.philo.common;

/**
 * Événements émis par un philosophe vers le moniteur.
 * <ul>
 *   <li>HUNGRY : le philosophe a fini de penser et veut manger ;</li>
 *   <li>EATING : il a obtenu ses fourchettes et commence à manger ;</li>
 *   <li>DONE   : il a fini de manger et va rendre ses fourchettes.</li>
 * </ul>
 * DONE est émis AVANT la restitution des fourchettes : le moniteur reçoit donc toujours le DONE
 * d'un philosophe avant le EATING de celui qui récupère ses fourchettes (pas de faux conflit).
 */
public enum EventType {
    HUNGRY,
    EATING,
    DONE
}
