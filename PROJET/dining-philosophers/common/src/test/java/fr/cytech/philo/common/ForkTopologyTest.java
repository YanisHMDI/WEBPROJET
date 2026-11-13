package fr.cytech.philo.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForkTopologyTest {

    @Test
    void forksWrapAroundTheTable() {
        assertThat(ForkTopology.leftFork(0, 5)).isZero();
        assertThat(ForkTopology.rightFork(0, 5)).isEqualTo(1);
        assertThat(ForkTopology.rightFork(4, 5)).isZero();
    }

    @Test
    void neighboursWrapAroundTheTable() {
        assertThat(ForkTopology.leftNeighbour(0, 5)).isEqualTo(4);
        assertThat(ForkTopology.rightNeighbour(4, 5)).isZero();
    }

    @Test
    void invalidIdsAreRejected() {
        assertThatThrownBy(() -> ForkTopology.leftFork(5, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ForkTopology.leftFork(0, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
