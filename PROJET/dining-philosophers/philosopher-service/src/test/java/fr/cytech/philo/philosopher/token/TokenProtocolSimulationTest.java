package fr.cytech.philo.philosopher.token;

import fr.cytech.philo.common.LamportClock;
import fr.cytech.philo.philosopher.core.DiningStrategy;
import fr.cytech.philo.philosopher.core.Philosopher;
import fr.cytech.philo.philosopher.core.Sleeper;
import fr.cytech.philo.philosopher.token.Dispatcher.PeerDispatcher;
import fr.cytech.philo.philosopher.token.TokenMessages.TokenRelease;
import fr.cytech.philo.philosopher.token.TokenMessages.TokenRequest;
import fr.cytech.philo.philosopher.token.TokenMessages.TokenTransfer;
import fr.cytech.philo.philosopher.token.TokenStrategy.ForkAssignment;
import fr.cytech.philo.philosopher.token.TokenTransport.ChaosTransport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests de concurrence et de résilience du protocole à jetons.
 * <p>
 * N philosophes complets (vraie boucle, vrais threads, vrais délais) échangent leurs messages par un
 * réseau en mémoire, éventuellement perturbé ({@link ChaosTransport} : pertes, accusés perdus donc
 * doublons, latence). Un oracle global vérifie à chaque repas qu'aucune fourchette n'a deux utilisateurs.
 */
class TokenProtocolSimulationTest {

    private static final Logger log = LoggerFactory.getLogger(TokenProtocolSimulationTest.class);

    /** Vérité terrain : qui mange avec chaque fourchette. Ne fait pas partie du protocole. */
    static final class Oracle {
        final AtomicReferenceArray<Integer> users;
        final AtomicInteger violations = new AtomicInteger();
        final AtomicLong maxWaitMs = new AtomicLong();

        Oracle(int forks) {
            users = new AtomicReferenceArray<>(forks);
        }
    }

    /** Entoure la vraie stratégie : vérifie l'exclusion mutuelle et mesure l'attente. */
    record CheckedStrategy(int id, DiningStrategy delegate, Oracle oracle) implements DiningStrategy {
        @Override
        public void acquireForks() throws InterruptedException {
            long start = System.nanoTime();
            delegate.acquireForks();
            oracle.maxWaitMs.accumulateAndGet((System.nanoTime() - start) / 1_000_000, Math::max);
            for (int fork : delegate.currentForks()) {
                if (!oracle.users.compareAndSet(fork, null, id)) {
                    oracle.violations.incrementAndGet();
                    log.error("VIOLATION : P{} mange avec F{} déjà utilisée par P{}", id, fork, oracle.users.get(fork));
                }
            }
        }

        @Override
        public void releaseForks() {
            for (int fork : delegate.currentForks()) {
                oracle.users.compareAndSet(fork, id, null);
            }
            delegate.releaseForks();
        }

        @Override
        public List<Integer> currentForks() {
            return delegate.currentForks();
        }
    }

    /** Livre chaque message directement au nœud destinataire (sur le thread d'envoi de l'émetteur). */
    static final class InMemoryNetwork implements TokenTransport {
        final TokenNode[] nodes;

        InMemoryNetwork(int n) {
            nodes = new TokenNode[n];
        }

        @Override
        public void sendRequest(int to, TokenRequest request) {
            nodes[to].onRequest(request);
        }

        @Override
        public void sendToken(int to, TokenTransfer token) {
            nodes[to].onToken(token);
        }

        @Override
        public void sendRelease(int to, TokenRelease release) {
            nodes[to].onRelease(release);
        }
    }

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
        Oracle oracle = new Oracle(s.n());
        InMemoryNetwork network = new InMemoryNetwork(s.n());
        TokenTransport transport = s.dropRate() > 0 || !s.maxDelay().isZero()
                ? new ChaosTransport(network, s.dropRate(), s.maxDelay())
                : network;

        List<PeerDispatcher> dispatchers = new ArrayList<>();
        List<Philosopher> philosophers = new ArrayList<>();
        for (int i = 0; i < s.n(); i++) {
            PeerDispatcher dispatcher = new PeerDispatcher(i, s.n());
            dispatchers.add(dispatcher);
            network.nodes[i] = new TokenNode(i, s.n(), new LamportClock(), transport, dispatcher, Duration.ofMillis(10));
            DiningStrategy strategy = new CheckedStrategy(i,
                    new TokenStrategy(network.nodes[i], ForkAssignment.of(s.rule()), s.n(), Duration.ofMillis(50)),
                    oracle);
            philosophers.add(new Philosopher(i, strategy, e -> { }, new LamportClock(),
                    new Philosopher.Timing(Duration.ZERO, s.thinkMax(), Duration.ofMillis(1), s.eatMax()),
                    Sleeper.REAL));
        }

        List<Thread> threads = philosophers.stream().map(p -> new Thread(p, "philo-" + p.id())).toList();
        threads.forEach(Thread::start);
        Thread.sleep(3000);
        philosophers.forEach(Philosopher::stop);
        threads.forEach(Thread::interrupt);
        for (Thread t : threads) {
            t.join(2000);
        }

        int[] meals = philosophers.stream().mapToInt(Philosopher::meals).toArray();
        log.info("[{}] repas = {}, attente max = {} ms, stats P0 = {}", s.name(), Arrays.toString(meals),
                oracle.maxWaitMs.get(), network.nodes[0].stats());

        // 1. sûreté : jamais deux philosophes avec la même fourchette
        assertThat(oracle.violations.get()).as("violations de l'exclusion mutuelle").isZero();

        // 2. vivacité / absence de famine : tout le monde a mangé, et plusieurs fois
        int min = Arrays.stream(meals).min().orElseThrow();
        int max = Arrays.stream(meals).max().orElseThrow();
        assertThat(min).as("repas du philosophe le moins servi " + Arrays.toString(meals)).isGreaterThanOrEqualTo(5);

        // 3. équité : personne n'a mangé beaucoup moins que les autres
        assertThat(min).as("équité : min repas vs max repas " + Arrays.toString(meals)).isGreaterThanOrEqualTo(max / 4);

        // 4. conservation : une fois le réseau calmé, chaque jeton est à exactement un endroit
        awaitQuiet(network.nodes, s.n());
        for (int fork = 0; fork < s.n(); fork++) {
            int f = fork;
            long holders = Arrays.stream(network.nodes).filter(node -> node.holds(f)).count();
            assertThat(holders).as("détenteurs du jeton F" + fork).isEqualTo(1);
        }
        dispatchers.forEach(PeerDispatcher::close);
    }

    /** Attend que plus aucun jeton ne soit en transit (les réémissions finissent par passer). */
    private static void awaitQuiet(TokenNode[] nodes, int n) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            boolean quiet = Arrays.stream(nodes).allMatch(node -> node.state().inTransit().isEmpty());
            if (quiet) {
                return;
            }
            Thread.sleep(20);
        }
    }
}
