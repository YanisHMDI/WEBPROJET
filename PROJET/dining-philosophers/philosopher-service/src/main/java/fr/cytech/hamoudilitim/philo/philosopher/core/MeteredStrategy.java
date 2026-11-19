package fr.cytech.hamoudilitim.philo.philosopher.core;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.List;

/**
 * Partie 6 — supervision : entoure la stratégie choisie et publie ses mesures pour Prometheus.
 * <ul>
 *   <li>{@code philo_fork_wait_seconds} : temps passé à attendre ses fourchettes (histogramme) ;</li>
 *   <li>{@code philo_meals_total} : nombre de repas.</li>
 * </ul>
 * Le comportement de la stratégie n'est pas modifié.
 */
public class MeteredStrategy implements DiningStrategy {

    private final DiningStrategy delegate;
    private final Timer wait;
    private final Counter meals;

    public MeteredStrategy(DiningStrategy delegate, MeterRegistry registry, int id, String mode) {
        this.delegate = delegate;
        this.wait = Timer.builder("philo.fork.wait")
                .description("attente des fourchettes")
                .tag("philosopher", String.valueOf(id)).tag("mode", mode)
                .publishPercentileHistogram()
                .register(registry);
        this.meals = Counter.builder("philo.meals")
                .description("repas terminés")
                .tag("philosopher", String.valueOf(id)).tag("mode", mode)
                .register(registry);
    }

    @Override
    public void acquireForks() throws InterruptedException {
        long start = System.nanoTime();
        delegate.acquireForks();
        wait.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void releaseForks() {
        delegate.releaseForks();
        meals.increment();
    }

    @Override
    public List<Integer> currentForks() {
        return delegate.currentForks();
    }

    /** La stratégie mesurée (celle de la partie choisie). */
    public DiningStrategy delegate() {
        return delegate;
    }
}
