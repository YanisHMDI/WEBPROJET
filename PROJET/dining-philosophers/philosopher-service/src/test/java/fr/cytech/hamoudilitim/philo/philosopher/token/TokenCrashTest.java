package fr.cytech.hamoudilitim.philo.philosopher.token;

import fr.cytech.hamoudilitim.philo.philosopher.core.DiningStrategy;
import fr.cytech.hamoudilitim.philo.philosopher.core.DiningTable;
import fr.cytech.hamoudilitim.philo.philosopher.core.ForkAssignment;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partie 4 — crash définitif d'un philosophe qui détient des jetons (limite connue du protocole).
 * <p>
 * P1 « tombe » pendant qu'il mange : il garde ses jetons et plus aucun message ne lui parvient.
 * <ul>
 *   <li>la sûreté tient toujours : personne n'utilise les fourchettes bloquées chez P1 ;</li>
 *   <li>sans mécanisme d'abandon, la vivacité s'effondre en cascade : un affamé qui attend une fourchette
 *       bloquée garde les jetons qu'il a déjà, ce qui bloque d'autres philosophes, etc. ;</li>
 *   <li>avec l'abandon après un délai ({@code give-up-after}), seuls les philosophes dont la propre
 *       fourchette est bloquée chez P1 restent bloqués : les autres continuent de manger, plus lentement.</li>
 * </ul>
 */
class TokenCrashTest {

    private static final Logger log = LoggerFactory.getLogger(TokenCrashTest.class);
    private static final int N = 5;
    private static final int CRASHED = 1;

    private record Outcome(int violations, int[] mealsAfterCrash, List<Integer> stuckForks) {
    }

    private Outcome runWithCrash(Duration giveUpAfter) throws Exception {
        try (InMemoryNetwork network = new InMemoryNetwork(N, 0, Duration.ZERO)) {
            List<DiningStrategy> strategies = new ArrayList<>();
            for (int i = 0; i < N; i++) {
                strategies.add(new TokenStrategy(network.node(i), ForkAssignment.of(ForkAssignment.Rule.RANDOM), N,
                        Duration.ofMillis(50), giveUpAfter));
            }
            DiningTable table = new DiningTable(strategies, N, DiningTable.fast(Duration.ofMillis(5), Duration.ofMillis(5)));
            table.start();
            Thread.sleep(500);

            // P1 tombe pendant un repas : il emporte au moins deux jetons
            while (network.node(CRASHED).currentForks().isEmpty()) {
                Thread.onSpinWait();
            }
            network.crash(CRASHED);
            // jetons perdus = ceux que P1 détient, plus ceux qu'il était en train d'envoyer (ils ne partiront jamais)
            Thread.sleep(50);
            TokenNode.State state = network.node(CRASHED).state();
            List<Integer> stuck = new ArrayList<>(state.heldTokens());
            stuck.addAll(state.inTransit().keySet());
            stuck.sort(null);

            Thread.sleep(1500);                                        // laisse la situation se stabiliser
            int[] after = table.mealsDuring(Duration.ofSeconds(2));
            table.stop();
            log.info("give-up-after={} : jetons bloqués chez P1 = {}, repas après le crash = {}", giveUpAfter, stuck,
                    Arrays.toString(after));
            return new Outcome(table.violations(), after, stuck);
        }
    }

    @Test
    void withoutGivingUpTheCrashBlocksEveryoneButSafetyHolds() throws Exception {
        Outcome o = runWithCrash(Duration.ZERO);

        assertThat(o.violations()).as("sûreté").isZero();
        assertThat(o.stuckForks()).hasSizeGreaterThanOrEqualTo(2);
        int others = IntStream.range(0, N).filter(i -> i != CRASHED).map(i -> o.mealsAfterCrash()[i]).sum();
        assertThat(others).as("repas des autres philosophes, après stabilisation").isZero();
    }

    @Test
    void givingUpKeepsAliveEveryoneWhoseOwnForkIsNotStuck() throws Exception {
        Outcome o = runWithCrash(Duration.ofMillis(200));

        assertThat(o.violations()).as("sûreté").isZero();
        for (int i = 0; i < N; i++) {
            if (i == CRASHED || o.stuckForks().contains(i)) {
                continue;   // P_i demande toujours F_i : si F_i est chez P1, il ne peut plus manger
            }
            // ralenti (attentes de give-up-after quand la seconde fourchette tirée est bloquée), mais vivant
            assertThat(o.mealsAfterCrash()[i]).as("repas de P" + i + " après le crash de P1").isPositive();
        }
    }
}
