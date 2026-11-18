package fr.cytech.hamoudilitim.philo.philosopher.graph;

import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.DegreeResult;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.DegreeTask;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.ExpandRequest;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.PartitionLoad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.IntFunction;

/**
 * Partie 5 — rôle Master : découpe le graphe, distribue les tâches aux Workers, rassemble les résultats.
 * <p>
 * <b>Partitionnement.</b> Par nœud : le nœud v appartient à la partition v mod P (P = nombre de Workers).
 * Chaque partition reçoit les arcs qui touchent un de ses nœuds ; un arc entre deux partitions est envoyé
 * aux deux, mais chacune ne compte que l'extrémité qui lui appartient : pas de double comptage.
 * <p>
 * <b>Pannes et lenteurs.</b> Pour chaque partition, la tâche part sur un premier Worker. S'il échoue, elle est
 * relancée sur le suivant ; s'il ne répond pas dans le délai {@code taskTimeout}, une copie est lancée sur un
 * autre Worker et <b>le premier résultat arrivé gagne</b> (exécution spéculative). Les calculs étant
 * déterministes et chaque partition n'étant fusionnée qu'une fois, un résultat tardif est simplement ignoré :
 * le résultat final reste exact.
 */
public class GraphMaster implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GraphMaster.class);

    private final WorkerClient client;
    private final Duration taskTimeout;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "graph-master");
        t.setDaemon(true);
        return t;
    });

    public GraphMaster(WorkerClient client, Duration taskTimeout) {
        this.client = client;
        this.taskTimeout = taskTimeout;
    }

    /** Compteurs de tolérance aux pannes d'un travail. */
    public static final class JobStats {
        final AtomicInteger tasks = new AtomicInteger();
        final AtomicInteger reassigned = new AtomicInteger();
        final AtomicInteger speculative = new AtomicInteger();
        final AtomicInteger lateIgnored = new AtomicInteger();
    }

    public record DegreeReport(String jobId, int nodes, int edges, List<Integer> workers, long distributedMs,
                               long sequentialMs, boolean correct, long sumOfDegrees, int maxDegree, int tasks,
                               int reassigned, int speculative, int lateIgnored, int[] degrees) {
    }

    public record ReachReport(String jobId, int nodes, int edges, int source, int target, boolean reachable,
                              int levels, int visited, List<Integer> workers, long distributedMs, long sequentialMs,
                              boolean correct, int tasks, int reassigned, int speculative, int lateIgnored) {
    }

    // ------------------------------------------------------------------ degrés

    public DegreeReport degrees(Graph graph, List<Integer> workers) {
        requireWorkers(workers);
        String jobId = UUID.randomUUID().toString();
        JobStats stats = new JobStats();
        int partitions = workers.size();
        long start = System.nanoTime();

        // 1. partitionnement : chaque arc va à la partition de chacune de ses extrémités
        List<List<int[]>> parts = new ArrayList<>();
        for (int p = 0; p < partitions; p++) {
            parts.add(new ArrayList<>());
        }
        for (int[] e : graph.edges()) {
            int pu = Graph.owner(e[0], partitions);
            int pv = Graph.owner(e[1], partitions);
            parts.get(pu).add(e);
            if (pv != pu) {
                parts.get(pv).add(e);
            }
        }

        // 2. une tâche par partition, en parallèle, avec relance
        List<Future<DegreeResult>> results = new ArrayList<>();
        for (int p = 0; p < partitions; p++) {
            DegreeTask task = new DegreeTask(jobId, p, partitions, parts.get(p).toArray(new int[0][]));
            int partition = p;
            results.add(executor.submit(() ->
                    runWithFailover(partition, workers, w -> client.degree(w, task), stats)));
        }

        // 3. fusion : chaque nœud n'appartient qu'à une partition, donc n'est écrit qu'une fois
        int[] degree = new int[graph.nodes()];
        for (Future<DegreeResult> f : results) {
            DegreeResult r = join(f);
            for (int i = 0; i < r.nodes().length; i++) {
                degree[r.nodes()[i]] = r.degrees()[i];
            }
        }
        long distributedMs = elapsedMs(start);

        long seqStart = System.nanoTime();
        int[] reference = graph.sequentialDegrees();
        long sequentialMs = elapsedMs(seqStart);

        long sum = Arrays.stream(degree).asLongStream().sum();
        return new DegreeReport(jobId, graph.nodes(), graph.edges().length, List.copyOf(workers), distributedMs,
                sequentialMs, Arrays.equals(degree, reference), sum, Arrays.stream(degree).max().orElse(0),
                stats.tasks.get(), stats.reassigned.get(), stats.speculative.get(), stats.lateIgnored.get(), degree);
    }

    // ------------------------------------------------------------------ accessibilité s → t

    /**
     * Parcours en largeur par niveaux. Chaque Worker garde les successeurs des nœuds de sa partition ; à chaque
     * niveau, le Master lui envoie les nœuds de la frontière qui lui appartiennent, fusionne les successeurs,
     * et s'arrête dès que t est atteint ou que la frontière est vide.
     */
    public ReachReport reachable(Graph graph, int source, int target, List<Integer> workers) {
        requireWorkers(workers);
        if (source < 0 || source >= graph.nodes() || target < 0 || target >= graph.nodes()) {
            throw new IllegalArgumentException("source ou cible hors du graphe");
        }
        String jobId = UUID.randomUUID().toString();
        JobStats stats = new JobStats();
        int partitions = workers.size();
        long start = System.nanoTime();

        List<Map<Integer, List<Integer>>> lists = new ArrayList<>();
        for (int p = 0; p < partitions; p++) {
            lists.add(new HashMap<>());
        }
        for (int[] e : graph.edges()) {
            lists.get(Graph.owner(e[0], partitions)).computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
        }
        List<PartitionLoad> loads = new ArrayList<>();
        for (int p = 0; p < partitions; p++) {
            Map<Integer, int[]> adjacency = new HashMap<>();
            lists.get(p).forEach((node, succ) -> adjacency.put(node, succ.stream().mapToInt(Integer::intValue).toArray()));
            loads.add(new PartitionLoad(jobId, p, partitions, adjacency));
        }

        // hôte courant de chaque partition (change si un Worker tombe)
        AtomicIntegerArray host = new AtomicIntegerArray(partitions);
        try {
            List<Future<Integer>> loaded = new ArrayList<>();
            for (int p = 0; p < partitions; p++) {
                int partition = p;
                loaded.add(executor.submit(() -> runWithFailover(partition, workers, w -> {
                    client.load(w, loads.get(partition));
                    return w;
                }, stats)));
            }
            for (int p = 0; p < partitions; p++) {
                host.set(p, join(loaded.get(p)));
            }

            boolean[] visited = new boolean[graph.nodes()];
            visited[source] = true;
            int visitedCount = 1;
            List<Integer> frontier = List.of(source);
            int levels = 0;
            boolean found = source == target;
            while (!found && !frontier.isEmpty()) {
                levels++;
                Map<Integer, List<Integer>> byPartition = new HashMap<>();
                for (int node : frontier) {
                    byPartition.computeIfAbsent(Graph.owner(node, partitions), k -> new ArrayList<>()).add(node);
                }
                List<Future<int[]>> expansions = new ArrayList<>();
                for (var entry : byPartition.entrySet()) {
                    int p = entry.getKey();
                    ExpandRequest request = new ExpandRequest(jobId, p,
                            entry.getValue().stream().mapToInt(Integer::intValue).toArray());
                    List<Integer> candidates = rotate(workers, workers.indexOf(host.get(p)));
                    expansions.add(executor.submit(() -> runWithFailover(0, candidates, w -> {
                        if (w != host.get(p)) {
                            client.load(w, loads.get(p));   // l'hôte est tombé : on recharge la partition ici
                        }
                        int[] succ = client.expand(w, request).successors();
                        host.set(p, w);
                        return succ;
                    }, stats)));
                }
                List<Integer> next = new ArrayList<>();
                for (Future<int[]> f : expansions) {
                    for (int v : join(f)) {
                        if (!visited[v]) {
                            visited[v] = true;
                            visitedCount++;
                            next.add(v);
                            found |= v == target;
                        }
                    }
                }
                frontier = next;
            }
            long distributedMs = elapsedMs(start);

            long seqStart = System.nanoTime();
            boolean reference = graph.sequentialReachable(source, target);
            long sequentialMs = elapsedMs(seqStart);

            return new ReachReport(jobId, graph.nodes(), graph.edges().length, source, target, found, levels,
                    visitedCount, List.copyOf(workers), distributedMs, sequentialMs, found == reference,
                    stats.tasks.get(), stats.reassigned.get(), stats.speculative.get(), stats.lateIgnored.get());
        } finally {
            for (int w : workers) {
                executor.submit(() -> {
                    try {
                        client.drop(w, jobId);
                    } catch (RuntimeException ignored) {
                        // Worker en panne : rien à nettoyer
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------ tolérance aux pannes

    /**
     * Exécute une tâche en essayant les Workers dans l'ordre (en commençant par le Worker « naturel » de la
     * partition). Échec → Worker suivant ; pas de réponse dans {@code taskTimeout} → copie sur le Worker
     * suivant, et le premier résultat obtenu gagne.
     */
    <T> T runWithFailover(int partition, List<Integer> workers, IntFunction<T> call, JobStats stats) {
        List<Integer> candidates = rotate(workers, partition % workers.size());
        CompletionService<T> race = new ExecutorCompletionService<>(executor);
        int next = 0;
        int running = 0;
        int timeouts = 0;
        List<String> errors = new ArrayList<>();
        race.submit(task(call, candidates.get(next++), stats));
        running++;
        try {
            while (running > 0) {
                Future<T> done = race.poll(taskTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (done == null) {
                    if (next < candidates.size()) {
                        stats.speculative.incrementAndGet();
                        log.warn("partition {} : pas de réponse en {}, copie lancée sur P{}", partition, taskTimeout,
                                candidates.get(next));
                        race.submit(task(call, candidates.get(next++), stats));
                        running++;
                    } else if (++timeouts > 3) {
                        break;
                    }
                    continue;
                }
                running--;
                try {
                    T result = done.get();
                    stats.lateIgnored.addAndGet(running); // les copies encore en cours seront ignorées
                    return result;
                } catch (ExecutionException e) {
                    errors.add(e.getCause().getMessage());
                    if (next < candidates.size()) {
                        stats.reassigned.incrementAndGet();
                        log.warn("partition {} : échec ({}), tâche relancée sur P{}", partition,
                                e.getCause().getMessage(), candidates.get(next));
                        race.submit(task(call, candidates.get(next++), stats));
                        running++;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new IllegalStateException("partition " + partition + " : aucun Worker n'a répondu " + errors);
    }

    private static <T> java.util.concurrent.Callable<T> task(IntFunction<T> call, int worker, JobStats stats) {
        return () -> {
            stats.tasks.incrementAndGet();
            return call.apply(worker);
        };
    }

    private static List<Integer> rotate(List<Integer> list, int from) {
        List<Integer> rotated = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            rotated.add(list.get((from + i) % list.size()));
        }
        return rotated;
    }

    private static <T> T join(Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompu", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        }
    }

    private static void requireWorkers(List<Integer> workers) {
        if (workers == null || workers.isEmpty()) {
            throw new IllegalArgumentException("au moins un Worker est nécessaire");
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
