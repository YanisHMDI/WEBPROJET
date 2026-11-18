package fr.cytech.hamoudilitim.philo.philosopher.graph;

import java.util.Map;

/**
 * Partie 5 — messages échangés en JSON entre le Master et les Workers.
 */
public final class GraphMessages {

    private GraphMessages() {
    }

    /**
     * Calcul des degrés d'une partition. Le Worker reçoit les arcs qui touchent au moins un de ses nœuds,
     * et ne compte que les extrémités qui lui appartiennent : un arc entre deux partitions est envoyé aux deux,
     * mais chacune ne compte que « son » bout, d'où l'absence de double comptage.
     */
    public record DegreeTask(String jobId, int partition, int partitions, int[][] edges) {
    }

    /** Degrés non nuls des nœuds de la partition ({@code nodes[i]} a le degré {@code degrees[i]}). */
    public record DegreeResult(String jobId, int partition, int worker, int[] nodes, int[] degrees) {
    }

    /** Accessibilité : liste des successeurs de chaque nœud d'une partition, conservée par le Worker. */
    public record PartitionLoad(String jobId, int partition, int partitions, Map<Integer, int[]> adjacency) {
    }

    /** Accessibilité : « donne-moi les successeurs de ces nœuds de la frontière ». */
    public record ExpandRequest(String jobId, int partition, int[] frontier) {
    }

    public record ExpandResult(String jobId, int partition, int worker, int[] successors) {
    }
}
