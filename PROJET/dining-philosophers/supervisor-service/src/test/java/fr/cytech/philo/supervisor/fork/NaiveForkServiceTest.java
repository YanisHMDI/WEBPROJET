package fr.cytech.philo.supervisor.fork;

import fr.cytech.philo.common.ForkTopology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jeu de tests qui RECENSE LES FAILLES du superviseur naïf (partie 2).
 * Ces tests passent parce que les failles existent : ils documentent le comportement fautif.
 */
class NaiveForkServiceTest {

    private static final int N = 5;

    @Test
    @DisplayName("Faille 1 — interblocage : tout le monde tient sa gauche et attend sa droite")
    void circularWaitDeadlock() {
        ForkTable table = new ForkTable(N);
        NaiveForkService service = new NaiveForkService(table, RaceWindow.NONE);

        // Chaque philosophe obtient sa fourchette gauche (scénario parfaitement possible).
        for (int p = 0; p < N; p++) {
            assertThat(service.acquire(ForkTopology.leftFork(p, N), p)).isTrue();
        }

        // Plus aucune fourchette droite n'est disponible : personne ne peut manger,
        // et aucun ne rendra sa gauche puisqu'il attend sa droite → attente circulaire.
        for (int p = 0; p < N; p++) {
            int right = ForkTopology.rightFork(p, N);
            assertThat(service.isAvailable(right)).isFalse();
            assertThat(table.ownerOf(right)).isEqualTo(ForkTopology.rightNeighbour(p, N));
        }
    }

    @Test
    @DisplayName("Faille 2 — course check-then-act : la même fourchette est donnée à deux philosophes")
    void checkThenActRace() throws Exception {
        ForkTable table = new ForkTable(N);
        NaiveForkService service = new NaiveForkService(table, new RaceWindow(Duration.ofMillis(50)));

        // P0 (fourchette droite = F1) et P1 (fourchette gauche = F1) demandent F1 en même temps.
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int p : new int[]{0, 1}) {
            results.add(pool.submit(() -> {
                start.await();
                return service.acquire(1, p);
            }));
        }
        start.countDown();
        boolean first = results.get(0).get();
        boolean second = results.get(1).get();
        pool.shutdown();

        assertThat(first).isTrue();
        assertThat(second).isTrue();             // les deux ont "obtenu" F1…
        assertThat(table.violations()).isPositive(); // …ce qui est une violation de l'exclusion mutuelle
    }

    @Test
    @DisplayName("Faille 3 — un philosophe peut libérer une fourchette qu'il ne possède pas")
    void releaseByNonOwner() {
        ForkTable table = new ForkTable(N);
        NaiveForkService service = new NaiveForkService(table, RaceWindow.NONE);

        service.acquire(2, 2);
        service.release(2, 4); // P4 n'a jamais eu F2

        assertThat(service.isAvailable(2)).isTrue();
        assertThat(service.acquire(2, 1)).isTrue(); // P1 l'obtient alors que P2 croit toujours l'avoir
    }

    @Test
    @DisplayName("Faille 4 — le superviseur ne vérifie pas que le philosophe a déjà l'autre fourchette")
    void noGlobalConsistency() {
        ForkTable table = new ForkTable(N);
        NaiveForkService service = new NaiveForkService(table, RaceWindow.NONE);

        // P0 accapare des fourchettes qui ne sont même pas les siennes.
        assertThat(service.acquire(3, 0)).isTrue();
        assertThat(service.acquire(4, 0)).isTrue();
        assertThat(table.snapshot()).containsExactly(null, null, null, 0, 0);
    }
}
