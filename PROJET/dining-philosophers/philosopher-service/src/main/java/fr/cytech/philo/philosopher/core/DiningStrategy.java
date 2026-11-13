package fr.cytech.philo.philosopher.core;

/**
 * Point d'extension principal du projet : la façon dont un philosophe obtient
 * et rend ses fourchettes. La boucle {@link Philosopher} ne change jamais ;
 * chaque partie de l'énoncé = une nouvelle implémentation de cette interface.
 */
public interface DiningStrategy {

    /** Bloque jusqu'à ce que le philosophe ait le droit de manger. */
    void acquireForks() throws InterruptedException;

    /** Rend les fourchettes. Doit être sûr même si appelé après une interruption. */
    void releaseForks();
}
