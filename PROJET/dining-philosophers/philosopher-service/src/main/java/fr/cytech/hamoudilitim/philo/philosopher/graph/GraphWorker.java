package fr.cytech.hamoudilitim.philo.philosopher.graph;

import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.DegreeResult;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.DegreeTask;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.ExpandRequest;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.ExpandResult;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.PartitionLoad;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Partie 5 — rôle Worker d'un philosophe : calcule sur la partition qu'on lui confie.
 * <ul>
 *   <li>degrés : sans état, tout est dans la tâche ;</li>
 *   <li>accessibilité : garde en mémoire les partitions chargées (par travail et par partition), puis
 *       répond aux demandes d'expansion de la frontière.</li>
 * </ul>
 * Chaque calcul est <b>déterministe</b> : refaire une tâche sur un autre Worker donne le même résultat, c'est
 * ce qui permet au Master de relancer une tâche sans risque.
 */
public class GraphWorker {

    private final int id;
    private final Duration delay;
    private final Map<String, Map<Integer, Map<Integer, int[]>>> partitions = new ConcurrentHashMap<>();

    /** @param delay pause avant chaque réponse, pour simuler un Worker lent (tests de résilience) */
    public GraphWorker(int id, Duration delay) {
        this.id = id;
        this.delay = delay == null ? Duration.ZERO : delay;
    }

    public DegreeResult degree(DegreeTask task) {
        pause();
        int p = task.partition();
        int partitions = task.partitions();
        Map<Integer, Integer> degree = new java.util.HashMap<>();
        for (int[] e : task.edges()) {
            if (Graph.owner(e[0], partitions) == p) {
                degree.merge(e[0], 1, Integer::sum);
            }
            if (Graph.owner(e[1], partitions) == p) {
                degree.merge(e[1], 1, Integer::sum);
            }
        }
        int[] nodes = degree.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        int[] values = Arrays.stream(nodes).map(degree::get).toArray();
        return new DegreeResult(task.jobId(), p, id, nodes, values);
    }

    public void load(PartitionLoad load) {
        pause();
        partitions.computeIfAbsent(load.jobId(), j -> new ConcurrentHashMap<>())
                .put(load.partition(), load.adjacency());
    }

    public ExpandResult expand(ExpandRequest request) {
        pause();
        Map<Integer, int[]> adjacency = partitions.getOrDefault(request.jobId(), Map.of()).get(request.partition());
        if (adjacency == null) {
            // ce Worker n'a pas (ou plus, après un redémarrage) la partition : le Master la rechargera ailleurs
            throw new IllegalStateException("partition " + request.partition() + " du travail "
                    + request.jobId() + " absente sur P" + id);
        }
        int[] successors = Arrays.stream(request.frontier())
                .flatMap(node -> Arrays.stream(adjacency.getOrDefault(node, new int[0])))
                .distinct()
                .toArray();
        return new ExpandResult(request.jobId(), request.partition(), id, successors);
    }

    public void drop(String jobId) {
        partitions.remove(jobId);
    }

    public int loadedJobs() {
        return partitions.size();
    }

    private void pause() {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
