package fr.cytech.hamoudilitim.philo.philosopher.graph;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphTest {

    @Test
    void randomGraphIsReproducibleAndHasNoSelfLoop() {
        Graph a = Graph.random(100, 500, 42);
        Graph b = Graph.random(100, 500, 42);

        assertThat(a.edges()).isDeepEqualTo(b.edges());
        assertThat(Arrays.stream(a.edges()).noneMatch(e -> e[0] == e[1])).as("aucune boucle u → u").isTrue();
    }

    @Test
    void sumOfDegreesIsTwiceTheNumberOfEdges() {
        Graph g = Graph.random(1000, 5000, 7);

        assertThat(Arrays.stream(g.sequentialDegrees()).sum()).isEqualTo(2 * 5000);
    }

    @Test
    void reachabilityFollowsTheDirectionOfArcs() {
        // 0 → 1 → 2 → 3, et 4 isolé
        Graph g = new Graph(5, new int[][]{{0, 1}, {1, 2}, {2, 3}});

        assertThat(g.sequentialReachable(0, 3)).isTrue();
        assertThat(g.sequentialReachable(3, 0)).isFalse();
        assertThat(g.sequentialReachable(0, 4)).isFalse();
        assertThat(g.sequentialReachable(4, 4)).isTrue();
    }

    @Test
    void invalidArcsAreRejected() {
        assertThatThrownBy(() -> new Graph(3, new int[][]{{0, 3}})).isInstanceOf(IllegalArgumentException.class);
    }
}
