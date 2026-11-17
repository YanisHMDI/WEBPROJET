package fr.cytech.hamoudilitim.philo.philosopher.strategy;

import fr.cytech.hamoudilitim.philo.philosopher.core.DiningStrategy;

/**
 * Partie 1 — philosophes gâtés : chacun a ses propres couverts, rien n'est partagé.
 */
public class SpoiledStrategy implements DiningStrategy {

    @Override
    public void acquireForks() {
        // rien à attendre
    }

    @Override
    public void releaseForks() {
        // rien à rendre
    }
}
