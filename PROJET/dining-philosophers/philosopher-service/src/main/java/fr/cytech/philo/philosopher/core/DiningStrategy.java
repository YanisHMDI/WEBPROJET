package fr.cytech.philo.philosopher.core;

import java.util.List;

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

    /**
     * Fourchettes détenues pour le repas en cours, annoncées au moniteur dans l'événement EATING.
     * Liste vide = disposition classique F_i / F_{i+1}, que le moniteur sait reconstituer.
     */
    default List<Integer> currentForks() {
        return List.of();
    }
}
