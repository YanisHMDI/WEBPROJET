package fr.cytech.hamoudilitim.philo.philosopher.core;

import fr.cytech.hamoudilitim.philo.common.EventType;
import fr.cytech.hamoudilitim.philo.common.LamportClock;
import fr.cytech.hamoudilitim.philo.common.PhilosopherEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Boucle d'un philosophe : Penser → Avoir faim → Manger → Penser…
 * <p>
 * Indépendante de Spring et du réseau : toutes les dépendances sont injectées,
 * ce qui permet de la tester unitairement avec Mockito.
 */
public class Philosopher implements Runnable {

    public enum State { STOPPED, THINKING, HUNGRY, EATING }

    public record Timing(Duration thinkMin, Duration thinkMax, Duration eatMin, Duration eatMax) {
    }

    private static final Logger log = LoggerFactory.getLogger(Philosopher.class);

    private final int id;
    private final DiningStrategy strategy;
    private final EventReporter reporter;
    private final LamportClock clock;
    private final Timing timing;
    private final Sleeper sleeper;

    private final AtomicInteger meals = new AtomicInteger();
    private volatile State state = State.STOPPED;
    private volatile boolean running;

    public Philosopher(int id, DiningStrategy strategy, EventReporter reporter,
                       LamportClock clock, Timing timing, Sleeper sleeper) {
        this.id = id;
        this.strategy = strategy;
        this.reporter = reporter;
        this.clock = clock;
        this.timing = timing;
        this.sleeper = sleeper;
    }

    @Override
    public void run() {
        running = true;
        log.info("P{} démarre", id);
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                cycle();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            state = State.STOPPED;
            log.info("P{} s'arrête après {} repas", id, meals.get());
        }
    }

    /** Un tour complet. Visible pour les tests. */
    void cycle() throws InterruptedException {
        state = State.THINKING;
        sleeper.sleep(randomBetween(timing.thinkMin(), timing.thinkMax()));

        state = State.HUNGRY;
        emit(EventType.HUNGRY, List.of());
        strategy.acquireForks();

        try {
            state = State.EATING;
            emit(EventType.EATING, strategy.currentForks());
            sleeper.sleep(randomBetween(timing.eatMin(), timing.eatMax()));
            meals.incrementAndGet();
        } finally {
            // DONE part AVANT la restitution, même si le repas est interrompu : sinon un autre philosophe
            // pourrait prendre la fourchette et annoncer EATING avant que notre DONE n'arrive → faux conflit.
            emit(EventType.DONE, List.of());
            strategy.releaseForks();
        }
    }

    private void emit(EventType type, List<Integer> forks) {
        reporter.report(PhilosopherEvent.of(id, type, clock.tick(), forks));
    }

    static Duration randomBetween(Duration min, Duration max) {
        long lo = min.toMillis();
        long hi = max.toMillis();
        return Duration.ofMillis(lo == hi ? lo : ThreadLocalRandom.current().nextLong(lo, hi + 1));
    }

    public void stop() {
        running = false;
    }

    public int id() {
        return id;
    }

    public State state() {
        return state;
    }

    public int meals() {
        return meals.get();
    }

    public long clock() {
        return clock.current();
    }
}
