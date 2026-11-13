package fr.cytech.philo.supervisor.fork;

import fr.cytech.philo.common.ForkTopology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parties 3.1 et 3.2 — même scénario concurrent, avec et sans verrou.
 */
class PairStrategyConcurrencyTest {

    private static final int N = 5;

    /** P0 (F0,F1) et P1 (F1,F2) demandent exactement en même temps. */
    private static List<Boolean> neighboursRequestTogether(PairStrategy strategy) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int p : new int[]{0, 1}) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return strategy.tryAcquire(p);
                }));
            }
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> f : futures) {
                results.add(f.get(5, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("3.1 sans verrou : deux voisins obtiennent la même fourchette")
    void unsafeStrategyGrantsSharedFork() throws Exception {
        ForkTable table = new ForkTable(N);
        PairStrategy unsafe = new UnsafePairStrategy(table, new RaceWindow(Duration.ofMillis(50)));

        List<Boolean> results = neighboursRequestTogether(unsafe);

        assertThat(results).containsExactly(true, true);
        assertThat(table.violations()).isPositive();
    }

    @Test
    @DisplayName("3.2 avec verrou : un seul des deux voisins est servi")
    void lockedStrategyGrantsOnlyOne() throws Exception {
        ForkTable table = new ForkTable(N);
        PairStrategy locked = new LockedPairStrategy(table, new RaceWindow(Duration.ofMillis(50)));

        List<Boolean> results = neighboursRequestTogether(locked);

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(table.violations()).isZero();
    }

    @RepeatedTest(3)
    @DisplayName("3.2 stress : N philosophes × 300 repas, jamais deux utilisateurs pour une fourchette")
    void lockedStrategyUnderStress() throws Exception {
        ForkTable table = new ForkTable(N);
        PairStrategy locked = new LockedPairStrategy(table, RaceWindow.NONE);
        AtomicIntegerArray usersPerFork = new AtomicIntegerArray(N);
        AtomicLong exclusionBreaks = new AtomicLong();
        int mealsEach = 300;

        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int p = 0; p < N; p++) {
            int id = p;
            futures.add(pool.submit(() -> {
                start.await();
                int left = ForkTopology.leftFork(id, N);
                int right = ForkTopology.rightFork(id, N);
                int meals = 0;
                while (meals < mealsEach) {
                    if (!locked.tryAcquire(id)) {
                        Thread.onSpinWait();
                        continue;
                    }
                    // "manger" : on vérifie qu'on est seul sur chaque fourchette
                    if (usersPerFork.incrementAndGet(left) > 1) exclusionBreaks.incrementAndGet();
                    if (usersPerFork.incrementAndGet(right) > 1) exclusionBreaks.incrementAndGet();
                    usersPerFork.decrementAndGet(left);
                    usersPerFork.decrementAndGet(right);
                    locked.release(id);
                    meals++;
                }
                return meals;
            }));
        }
        start.countDown();
        for (Future<Integer> f : futures) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(mealsEach); // pas d'interblocage
        }
        pool.shutdown();

        assertThat(exclusionBreaks.get()).isZero();
        assertThat(table.violations()).isZero();
        assertThat(table.snapshot()).containsOnlyNulls();
    }

    @Test
    @DisplayName("3.2 : la libération par un non-propriétaire est ignorée")
    void lockedReleaseIgnoresNonOwner() {
        ForkTable table = new ForkTable(N);
        PairStrategy locked = new LockedPairStrategy(table, RaceWindow.NONE);

        assertThat(locked.tryAcquire(1)).isTrue();      // F1, F2
        locked.release(2);                              // P2 (F2, F3) tente de libérer
        assertThat(table.ownerOf(2)).isEqualTo(1);      // F2 reste à P1
    }
}
