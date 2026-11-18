package fr.cytech.hamoudilitim.philo.philosopher.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Partie 5 — graphe orienté G = (V, E), V = {0, …, n-1}, arcs stockés sous forme {u, v} (arc u → v).
 * <p>
 * Le <b>degré</b> d'un nœud est son nombre d'arcs incidents (entrants + sortants) : la somme des degrés vaut
 * donc toujours 2|E|, ce qui sert de contrôle contre les doubles comptages.
 * <p>
 * Contient aussi les versions <b>séquentielles</b> des calculs : elles servent de référence pour vérifier
 * les résultats distribués et de point de comparaison pour les performances.
 */
public record Graph(int nodes, int[][] edges) {

    public Graph {
        if (nodes < 1) {
            throw new IllegalArgumentException("un graphe a au moins un nœud");
        }
        for (int[] e : edges) {
            if (e.length != 2 || e[0] < 0 || e[0] >= nodes || e[1] < 0 || e[1] >= nodes) {
                throw new IllegalArgumentException("arc invalide");
            }
        }
    }

    /** Graphe aléatoire reproductible : {@code edgeCount} arcs tirés uniformément, sans boucle u → u. */
    public static Graph random(int nodes, int edgeCount, long seed) {
        if (nodes < 2 && edgeCount > 0) {
            throw new IllegalArgumentException("il faut au moins 2 nœuds pour tirer des arcs");
        }
        SplittableRandom rnd = new SplittableRandom(seed);
        int[][] edges = new int[edgeCount][];
        for (int i = 0; i < edgeCount; i++) {
            int u = rnd.nextInt(nodes);
            int v = rnd.nextInt(nodes - 1);
            if (v >= u) {
                v++;
            }
            edges[i] = new int[]{u, v};
        }
        return new Graph(nodes, edges);
    }

    /** Partitionnement par nœud : le nœud v appartient à la partition v mod P. */
    public static int owner(int node, int partitions) {
        return node % partitions;
    }

    public int[] sequentialDegrees() {
        int[] degree = new int[nodes];
        for (int[] e : edges) {
            degree[e[0]]++;
            degree[e[1]]++;
        }
        return degree;
    }

    /** Parcours en largeur classique : existe-t-il un chemin de {@code source} vers {@code target} ? */
    public boolean sequentialReachable(int source, int target) {
        List<List<Integer>> out = new ArrayList<>(nodes);
        for (int i = 0; i < nodes; i++) {
            out.add(new ArrayList<>());
        }
        for (int[] e : edges) {
            out.get(e[0]).add(e[1]);
        }
        boolean[] seen = new boolean[nodes];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        seen[source] = true;
        queue.add(source);
        while (!queue.isEmpty()) {
            int u = queue.poll();
            if (u == target) {
                return true;
            }
            for (int v : out.get(u)) {
                if (!seen[v]) {
                    seen[v] = true;
                    queue.add(v);
                }
            }
        }
        return false;
    }
}
