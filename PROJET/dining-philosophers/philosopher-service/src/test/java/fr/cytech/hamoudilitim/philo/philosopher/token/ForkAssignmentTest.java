package fr.cytech.hamoudilitim.philo.philosopher.token;

import fr.cytech.hamoudilitim.philo.philosopher.token.TokenStrategy.ForkAssignment;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ForkAssignmentTest {

    @Test
    void neighboursIsTheClassicRoundTable() {
        assertThat(ForkAssignment.neighbours().forksFor(4, 5)).containsExactly(4, 0);
    }

    @Test
    void randomRuleKeepsOwnForkAndCoversAllOthers() {
        ForkAssignment rule = ForkAssignment.random(new Random(42));
        Set<Integer> seconds = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            List<Integer> forks = rule.forksFor(2, 5);
            assertThat(forks.get(0)).isEqualTo(2);
            assertThat(forks.get(1)).isBetween(0, 4).isNotEqualTo(2);
            seconds.add(forks.get(1));
        }
        assertThat(seconds).containsExactlyInAnyOrder(0, 1, 3, 4); // aucune fourchette n'est exclue
    }
}
