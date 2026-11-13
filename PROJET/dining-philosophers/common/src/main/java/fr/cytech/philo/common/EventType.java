package fr.cytech.philo.common;

/**
 * Événements émis par un philosophe vers le moniteur.
 * <ul>
 *   <li>HUNGRY : le philosophe a fini de penser et veut manger ;</li>
 *   <li>EATING : il a obtenu ses fourchettes et commence à manger ;</li>
 *   <li>DONE   : il a fini de manger, rendu ses fourchettes et retourne penser.</li>
 * </ul>
 */
public enum EventType {
    HUNGRY,
    EATING,
    DONE
}
