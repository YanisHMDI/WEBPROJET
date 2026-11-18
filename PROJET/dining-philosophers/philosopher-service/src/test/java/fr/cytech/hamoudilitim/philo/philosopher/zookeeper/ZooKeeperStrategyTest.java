package fr.cytech.hamoudilitim.philo.philosopher.zookeeper;

import fr.cytech.hamoudilitim.philo.philosopher.core.DiningStrategy;
import fr.cytech.hamoudilitim.philo.philosopher.core.DiningTable;
import fr.cytech.hamoudilitim.philo.philosopher.core.ForkAssignment;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partie 3.4 — verrou distribué ZooKeeper. Un vrai serveur ZooKeeper tourne dans la JVM du test ; chaque
 * philosophe a son propre client, donc sa propre session, comme s'il était un processus séparé.
 */
class ZooKeeperStrategyTest {

    private static final Logger log = LoggerFactory.getLogger(ZooKeeperStrategyTest.class);
    private static final int N = 5;

    private TestingServer zookeeper;
    private final List<CuratorFramework> clients = new ArrayList<>();

    @BeforeEach
    void startZooKeeper() throws Exception {
        zookeeper = new TestingServer(true);
    }

    @AfterEach
    void stopZooKeeper() throws Exception {
        clients.forEach(CuratorFramework::close);
        zookeeper.close();
    }

    private CuratorFramework newClient() throws InterruptedException {
        CuratorFramework c = CuratorFrameworkFactory.newClient(zookeeper.getConnectString(), 3000, 3000,
                new RetryOneTime(100));
        c.start();
        c.blockUntilConnected(5, TimeUnit.SECONDS);
        clients.add(c);
        return c;
    }

    private ZooKeeperStrategy philosopher(int id, ForkAssignment assignment) throws InterruptedException {
        return new ZooKeeperStrategy(id, N, newClient(), assignment, "/philo");
    }

    @Test
    void noForkIsEverSharedAndEveryoneEatsFairly() throws Exception {
        List<DiningStrategy> strategies = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            strategies.add(philosopher(i, ForkAssignment.of(ForkAssignment.Rule.RANDOM)));
        }
        DiningTable table = new DiningTable(strategies, N, DiningTable.fast(Duration.ofMillis(5), Duration.ofMillis(5)));
        table.runFor(Duration.ofSeconds(3));

        int[] meals = table.meals();
        log.info("[ZooKeeper] repas = {}, attente moyenne = {} ms, max = {} ms", Arrays.toString(meals),
                String.format("%.1f", table.averageWaitMs()), table.maxWaitMs());
        int min = Arrays.stream(meals).min().orElseThrow();
        int max = Arrays.stream(meals).max().orElseThrow();
        assertThat(table.violations()).as("violations").isZero();
        assertThat(min).as("repas du moins servi " + Arrays.toString(meals)).isGreaterThanOrEqualTo(5);
        assertThat(min).as("équité " + Arrays.toString(meals)).isGreaterThanOrEqualTo(max / 4);
    }

    @Test
    void oppositeOrdersDoNotDeadlockThanksToTheGlobalOrder() throws Exception {
        // cas d'école de l'interblocage : P0 veut (F3 puis F1), P1 veut (F1 puis F3)
        List<DiningStrategy> strategies = List.of(
                philosopher(0, (p, n) -> List.of(3, 1)),
                philosopher(1, (p, n) -> List.of(1, 3)));
        DiningTable table = new DiningTable(strategies, N, DiningTable.fast(Duration.ZERO, Duration.ofMillis(1)));
        table.runFor(Duration.ofSeconds(2));

        assertThat(table.violations()).isZero();
        assertThat(Arrays.stream(table.meals()).min().orElseThrow()).as("les deux continuent de manger").isGreaterThan(20);
    }

    @Test
    void forksOfACrashedPhilosopherAreReleasedByZooKeeper() throws Exception {
        ZooKeeperStrategy p0 = philosopher(0, (p, n) -> List.of(0, 1));
        ZooKeeperStrategy p1 = philosopher(1, (p, n) -> List.of(1, 2));
        p0.acquireForks();                                     // P0 mange avec F0 et F1…

        // un verrou Curator se rend depuis le thread qui l'a pris : P1 mange et rend ses fourchettes dans son thread
        CompletableFuture<List<Integer>> p1Eats = CompletableFuture.supplyAsync(() -> {
            try {
                p1.acquireForks();                             // …P1 attend F1
                List<Integer> forks = p1.currentForks();
                p1.releaseForks();
                return forks;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        });
        Thread.sleep(300);
        assertThat(p1Eats).as("P1 attend tant que P0 tient F1").isNotDone();

        // P0 « meurt » : sa session expire, ZooKeeper supprime ses nœuds éphémères
        p0.client().getZookeeperClient().getZooKeeper().getTestable().injectSessionExpiration();

        // P1 obtient F1 sans que P0 l'ait jamais rendue
        assertThat(p1Eats.get(10, TimeUnit.SECONDS)).containsExactly(1, 2);
    }

    @Test
    void stoppedPhilosopherReleasesItsLocksEvenWhenInterrupted() throws Exception {
        ZooKeeperStrategy p0 = philosopher(0, (p, n) -> List.of(0, 1));
        ZooKeeperStrategy p1 = philosopher(1, (p, n) -> List.of(1, 2));
        p0.acquireForks();
        Thread.currentThread().interrupt();                    // comme un philosophe arrêté pendant son repas
        p0.releaseForks();
        assertThat(Thread.interrupted()).as("le drapeau d'interruption est conservé").isTrue();

        CompletableFuture<List<Integer>> p1Eats = CompletableFuture.supplyAsync(() -> {
            try {
                p1.acquireForks();
                List<Integer> forks = p1.currentForks();
                p1.releaseForks();
                return forks;
            } catch (InterruptedException e) {
                return List.of();
            }
        });
        assertThat(p1Eats.get(5, TimeUnit.SECONDS)).containsExactly(1, 2);
    }
}
