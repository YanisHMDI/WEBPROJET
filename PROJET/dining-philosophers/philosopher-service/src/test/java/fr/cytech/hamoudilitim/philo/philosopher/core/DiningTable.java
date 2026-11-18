package fr.cytech.hamoudilitim.philo.philosopher.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Aide de test : fait tourner N philosophes complets (vraie boucle, vrais threads) et vérifie, à chaque repas,
 * qu'aucune fourchette n'a deux utilisateurs. Cet « oracle » voit tout le système d'un coup, ce qu'aucun
 * philosophe ne peut faire : il ne fait pas partie des protocoles testés.
 */
public final class DiningTable {

    private static final Logger log = LoggerFactory.getLogger(DiningTable.class);

    private final AtomicReferenceArray<Integer> users;
    private final AtomicInteger violations = new AtomicInteger();
    private final AtomicLong maxWaitMs = new AtomicLong();
    private final AtomicLong totalWaitMs = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();
    private final List<Philosopher> philosophers = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();

    public DiningTable(List<DiningStrategy> strategies, int forks, Philosopher.Timing timing) {
        users = new AtomicReferenceArray<>(forks);
        for (int i = 0; i < strategies.size(); i++) {
            philosophers.add(new Philosopher(i, new Checked(i, strategies.get(i)), e -> { },
                    new fr.cytech.hamoudilitim.philo.common.LamportClock(), timing, Sleeper.REAL));
        }
    }

    public static Philosopher.Timing fast(Duration thinkMax, Duration eatMax) {
        return new Philosopher.Timing(Duration.ZERO, thinkMax, Duration.ofMillis(1), eatMax);
    }

    /** Entoure la vraie stratégie : contrôle l'exclusion mutuelle et mesure l'attente. */
    private final class Checked implements DiningStrategy {
        private final int id;
        private final DiningStrategy delegate;

        Checked(int id, DiningStrategy delegate) {
            this.id = id;
            this.delegate = delegate;
        }

        @Override
        public void acquireForks() throws InterruptedException {
            long start = System.nanoTime();
            delegate.acquireForks();
            long waited = (System.nanoTime() - start) / 1_000_000;
            maxWaitMs.accumulateAndGet(waited, Math::max);
            totalWaitMs.addAndGet(waited);
            waits.incrementAndGet();
            for (int fork : delegate.currentForks()) {
                if (!users.compareAndSet(fork, null, id)) {
                    violations.incrementAndGet();
                    log.error("VIOLATION : P{} mange avec F{} déjà utilisée par P{}", id, fork, users.get(fork));
                }
            }
        }

        @Override
        public void releaseForks() {
            for (int fork : delegate.currentForks()) {
                users.compareAndSet(fork, id, null);
            }
            delegate.releaseForks();
        }

        @Override
        public List<Integer> currentForks() {
            return delegate.currentForks();
        }
    }

    public void start() {
        for (Philosopher p : philosophers) {
            Thread t = new Thread(p, "philo-" + p.id());
            threads.add(t);
            t.start();
        }
    }

    public void runFor(Duration duration) throws InterruptedException {
        start();
        Thread.sleep(duration.toMillis());
        stop();
    }

    public void stop() throws InterruptedException {
        philosophers.forEach(Philosopher::stop);
        threads.forEach(Thread::interrupt);
        for (Thread t : threads) {
            t.join(3000);
        }
    }

    /** Repas faits par chacun pendant {@code window}. */
    public int[] mealsDuring(Duration window) throws InterruptedException {
        int[] before = meals();
        Thread.sleep(window.toMillis());
        int[] after = meals();
        for (int i = 0; i < after.length; i++) {
            after[i] -= before[i];
        }
        return after;
    }

    public int[] meals() {
        return philosophers.stream().mapToInt(Philosopher::meals).toArray();
    }

    public int totalMeals() {
        return Arrays.stream(meals()).sum();
    }

    public int violations() {
        return violations.get();
    }

    public long maxWaitMs() {
        return maxWaitMs.get();
    }

    public double averageWaitMs() {
        long n = waits.get();
        return n == 0 ? 0 : (double) totalWaitMs.get() / n;
    }

    public Philosopher philosopher(int i) {
        return philosophers.get(i);
    }
}
