package fr.cytech.hamoudilitim.philo.philosopher.graph;

import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMaster.DegreeReport;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMaster.ReachReport;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.DegreeResult;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.DegreeTask;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.ExpandRequest;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.ExpandResult;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.PartitionLoad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Partie 5 — le Master avec des Workers en mémoire, dont on peut provoquer la panne ou la lenteur.
 * Chaque résultat distribué est comparé au calcul séquentiel.
 */
class GraphMasterTest {

    /** Workers en mémoire ; un Worker « en panne » lève une exception, un Worker « lent » attend avant de répondre. */
    static final class LocalWorkers implements WorkerClient {
        final Map<Integer, GraphWorker> workers = new ConcurrentHashMap<>();
        final Set<Integer> down = ConcurrentHashMap.newKeySet();
        final Map<Integer, Duration> slow = new ConcurrentHashMap<>();
        /** Panne après un nombre d'expansions servies (pour tomber au milieu d'un parcours). */
        final Map<Integer, AtomicInteger> failAfterExpands = new ConcurrentHashMap<>();

        LocalWorkers(int... ids) {
            for (int id : ids) {
                workers.put(id, new GraphWorker(id, Duration.ZERO));
            }
        }

        private GraphWorker reach(int w) {
            if (down.contains(w)) {
                throw new IllegalStateException("P" + w + " injoignable");
            }
            Duration d = slow.get(w);
            if (d != null) {
                try {
                    Thread.sleep(d.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return workers.get(w);
        }

        @Override
        public DegreeResult degree(int worker, DegreeTask task) {
            return reach(worker).degree(task);
        }

        @Override
        public void load(int worker, PartitionLoad load) {
            reach(worker).load(load);
        }

        @Override
        public ExpandResult expand(int worker, ExpandRequest request) {
            AtomicInteger left = failAfterExpands.get(worker);
            if (left != null && left.decrementAndGet() < 0) {
                down.add(worker);
            }
            return reach(worker).expand(request);
        }

        @Override
        public void drop(int worker, String jobId) {
            reach(worker).drop(jobId);
        }
    }

    private final LocalWorkers workers = new LocalWorkers(1, 2, 3, 4);
    private final GraphMaster master = new GraphMaster(workers, Duration.ofMillis(200));

    @AfterEach
    void close() {
        master.close();
    }

    // ------------------------------------------------------------------ degrés

    @ParameterizedTest(name = "{0} Worker(s)")
    @ValueSource(ints = {1, 2, 3, 4})
    void degreesMatchTheSequentialComputation(int count) {
        Graph g = Graph.random(2_000, 10_000, 1);

        DegreeReport r = master.degrees(g, List.of(1, 2, 3, 4).subList(0, count));

        assertThat(r.correct()).isTrue();
        assertThat(r.degrees()).isEqualTo(g.sequentialDegrees());
        assertThat(r.sumOfDegrees()).as("pas de double comptage : somme = 2|E|").isEqualTo(2L * g.edges().length);
        assertThat(r.tasks()).isEqualTo(count);
    }

    @Test
    void tasksOfADeadWorkerAreReassigned() {
        workers.down.add(2);
        Graph g = Graph.random(1_000, 5_000, 2);

        DegreeReport r = master.degrees(g, List.of(1, 2, 3));

        assertThat(r.correct()).isTrue();
        assertThat(r.reassigned()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aSlowWorkerGetsASpeculativeCopyAndItsLateResultIsIgnored() throws Exception {
        workers.slow.put(1, Duration.ofMillis(800));            // bien au-delà du délai de 200 ms
        Graph g = Graph.random(1_000, 5_000, 3);

        DegreeReport r = master.degrees(g, List.of(1, 2, 3));

        assertThat(r.correct()).isTrue();
        assertThat(r.speculative()).isGreaterThanOrEqualTo(1);
        assertThat(r.lateIgnored()).as("le résultat de P1, arrivé après la copie, est ignoré").isGreaterThanOrEqualTo(1);
        assertThat(r.distributedMs()).as("on n'attend pas le Worker lent").isLessThan(800);
    }

    @Test
    void failsCleanlyWhenEveryWorkerIsDown() {
        workers.down.addAll(List.of(1, 2));

        assertThatThrownBy(() -> master.degrees(Graph.random(100, 300, 4), List.of(1, 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aucun Worker");
    }

    // ------------------------------------------------------------------ accessibilité

    @Test
    void reachabilityOnASmallKnownGraph() {
        // 0 → 1 → 2 → 3, 4 → 0, et 5 isolé
        Graph g = new Graph(6, new int[][]{{0, 1}, {1, 2}, {2, 3}, {4, 0}});
        List<Integer> two = List.of(1, 2);

        assertThat(master.reachable(g, 0, 3, two).reachable()).isTrue();
        assertThat(master.reachable(g, 4, 3, two).reachable()).isTrue();
        assertThat(master.reachable(g, 3, 0, two).reachable()).isFalse();
        assertThat(master.reachable(g, 0, 5, two).reachable()).isFalse();
        assertThat(master.reachable(g, 5, 5, two).reachable()).isTrue();
        assertThat(master.reachable(g, 0, 3, two).levels()).isEqualTo(3);
    }

    @Test
    void reachabilityMatchesTheSequentialSearchOnRandomGraphs() {
        Random rnd = new Random(5);
        for (int i = 0; i < 20; i++) {
            Graph g = Graph.random(300, 350, i);               // peu d'arcs : beaucoup de paires non accessibles
            int s = rnd.nextInt(300);
            int t = rnd.nextInt(300);

            ReachReport r = master.reachable(g, s, t, List.of(1, 2, 3));

            assertThat(r.correct()).as("graphe %d, %d → %d", i, s, t).isTrue();
        }
    }

    @Test
    void aWorkerDyingInTheMiddleOfTheSearchIsReplaced() {
        // chaîne 0 → 1 → … → 29 : avec 3 partitions (v mod 3), chaque niveau tombe sur la partition suivante.
        // P2 héberge la partition 1 (nœuds 1, 4, 7…) : il sert le niveau 2, puis tombe au niveau 5.
        int[][] chain = new int[29][];
        for (int i = 0; i < 29; i++) {
            chain[i] = new int[]{i, i + 1};
        }
        Graph g = new Graph(30, chain);
        workers.failAfterExpands.put(2, new AtomicInteger(1));

        ReachReport r = master.reachable(g, 0, 29, List.of(1, 2, 3));

        assertThat(r.reachable()).isTrue();
        assertThat(r.levels()).isEqualTo(29);
        assertThat(r.reassigned()).as("la partition de P2 a été rechargée ailleurs").isGreaterThanOrEqualTo(1);
    }

    @Test
    void partitionsAreDroppedFromWorkersAtTheEnd() throws Exception {
        master.reachable(Graph.random(100, 300, 8), 0, 50, List.of(1, 2));
        Thread.sleep(200);                                     // le nettoyage se fait en arrière-plan

        assertThat(workers.workers.get(1).loadedJobs()).isZero();
        assertThat(workers.workers.get(2).loadedJobs()).isZero();
    }
}
