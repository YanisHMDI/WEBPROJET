package fr.cytech.hamoudilitim.philo.philosopher.core;

import java.time.Duration;

/**
 * Abstraction de Thread.sleep, pour pouvoir tester la boucle sans attendre réellement.
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    Sleeper REAL = d -> {
        if (!d.isZero() && !d.isNegative()) {
            Thread.sleep(d.toMillis());
        }
    };
}
