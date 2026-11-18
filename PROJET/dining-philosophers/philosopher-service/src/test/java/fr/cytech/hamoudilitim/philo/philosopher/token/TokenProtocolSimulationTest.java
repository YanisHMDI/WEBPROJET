package fr.cytech.hamoudilitim.philo.philosopher.token;

import fr.cytech.hamoudilitim.philo.philosopher.core.DiningStrategy;
import fr.cytech.hamoudilitim.philo.philosopher.core.DiningTable;
import fr.cytech.hamoudilitim.philo.philosopher.core.ForkAssignment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests de concurrence et de résilience du protocole à jetons.
 * <p>
 * N philosophes complets échangent leurs messages par un réseau en mémoire, éventuellement perturbé
 * (pertes, accusés perdus donc doublons, latence). {@link DiningTable} vérifie à chaque repas qu'aucune
 * fourchette n'a deux utilisateurs.
 */
class TokenProtocolSimulationTest {

    private static final Logger log = LoggerFactory.getLogger(TokenProtocolSimulationTest.class);

    record Scenario(String name, int n, ForkAssignment.Rule rule, double dropRate, Duration maxDelay,
                    Duration thinkMax, Duration eatMax) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> scenarios() {
        return Stream.of(
                new Scenario("5 philosophes, fourchettes quelconques", 5, ForkAssignment.Rule.RANDOM,
                        0, Duration.ZERO, Duration.ofMillis(5), Duration.ofMillis(5)),
                new Scenario("5 philosophes, table ronde classique", 5, ForkAssignment.Rule.NEIGHBOURS,
                        0, Duration.ZERO, Duration.ofMillis(5), Duration.ofMillis(5)),
                new Scenario("7 philosophes, aucune pause (contention maximale)", 7, ForkAssignment.Rule.RANDOM,
                        0, Duration.ZERO, Duration.ZERO, Duration.ofMillis(2)),
                new Scenario("5 philosophes, 20 % de pertes + doublons + latence", 5, ForkAssignment.Rule.RANDOM,
                        0.2, Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofMillis(5))
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void noForkIsEverSharedAndEveryoneEats(Scenario s) throws Exception {
        try (InMemoryNetwork network = new InMemoryNetwork(s.n(), s.dropRate(), s.maxDelay())) {
            List<DiningStrategy> strategies = new ArrayList<>();
            for (int i = 0; i < s.n(); i++) {
                strategies.add(new TokenStrategy(network.node(i), ForkAssignment.of(s.rule()), s.n(),
                        Duration.ofMillis(50)));
            }
            DiningTable table = new DiningTable(strategies, s.n(), DiningTable.fast(s.thinkMax(), s.eatMax()));
            table.runFor(Duration.ofSeconds(3));

            int[] meals = table.meals();
            log.info("[{}] repas = {}, attente moyenne = {} ms, max = {} ms, stats P0 = {}", s.name(),
                    Arrays.toString(meals), String.format("%.1f", table.averageWaitMs()), table.maxWaitMs(),
                    network.node(0).stats());

            // 1. sûreté : jamais deux philosophes avec la même fourchette
            assertThat(table.violations()).as("violations de l'exclusion mutuelle").isZero();

            // 2. vivacité / absence de famine : tout le monde a mangé, et plusieurs fois
            int min = Arrays.stream(meals).min().orElseThrow();
            int max = Arrays.stream(meals).max().orElseThrow();
            assertThat(min).as("repas du philosophe le moins servi " + Arrays.toString(meals)).isGreaterThanOrEqualTo(5);

            // 3. équité : personne n'a mangé beaucoup moins que les autres
            assertThat(min).as("équité : min repas vs max repas " + Arrays.toString(meals)).isGreaterThanOrEqualTo(max / 4);

            // 4. conservation : une fois le réseau calmé, chaque jeton est à exactement un endroit
            network.awaitQuiet();
            for (int fork = 0; fork < s.n(); fork++) {
                assertThat(network.holdersOf(fork)).as("détenteurs du jeton F" + fork).isEqualTo(1);
            }
        }
    }
}
