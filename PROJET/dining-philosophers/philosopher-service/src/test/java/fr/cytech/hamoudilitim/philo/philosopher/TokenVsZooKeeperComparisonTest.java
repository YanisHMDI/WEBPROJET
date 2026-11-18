package fr.cytech.hamoudilitim.philo.philosopher;

import fr.cytech.hamoudilitim.philo.philosopher.core.DiningStrategy;
import fr.cytech.hamoudilitim.philo.philosopher.core.DiningTable;
import fr.cytech.hamoudilitim.philo.philosopher.core.ForkAssignment;
import fr.cytech.hamoudilitim.philo.philosopher.token.InMemoryNetwork;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenNode;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenStrategy;
import fr.cytech.hamoudilitim.philo.philosopher.zookeeper.ZooKeeperStrategy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partie 3.4 — comparaison chiffrée des deux solutions dans les mêmes conditions (même N, mêmes durées,
 * même règle d'attribution, 3 s). Les mesures sont écrites dans les logs (ligne « COMPARAISON ») et
 * reportées dans docs/comparaison-zookeeper.md. Le test vérifie seulement la sûreté des deux.
 */
class TokenVsZooKeeperComparisonTest {

    private static final Logger log = LoggerFactory.getLogger(TokenVsZooKeeperComparisonTest.class);
    private static final Duration RUN = Duration.ofSeconds(3);

    @ParameterizedTest(name = "N = {0}")
    @ValueSource(ints = {3, 5, 9})
    void compareTokensAndZooKeeper(int n) throws Exception {
        // --- protocole à jetons (réseau en mémoire)
        DiningTable tokens;
        long tokenMessages;
        try (InMemoryNetwork network = new InMemoryNetwork(n, 0, Duration.ZERO)) {
            List<DiningStrategy> strategies = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                strategies.add(new TokenStrategy(network.node(i), ForkAssignment.of(ForkAssignment.Rule.RANDOM), n,
                        Duration.ofMillis(50)));
            }
            tokens = new DiningTable(strategies, n, DiningTable.fast(Duration.ofMillis(5), Duration.ofMillis(5)));
            tokens.runFor(RUN);
            tokenMessages = 0;
            for (int i = 0; i < n; i++) {
                TokenNode.Stats s = network.node(i).stats();
                tokenMessages += s.requestsSent() + s.tokensSent() + s.releasesSent();
            }
        }

        // --- ZooKeeper (vrai serveur, un client et une session par philosophe)
        DiningTable zk;
        try (TestingServer server = new TestingServer(true)) {
            List<CuratorFramework> clients = new ArrayList<>();
            List<DiningStrategy> strategies = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                CuratorFramework c = CuratorFrameworkFactory.newClient(server.getConnectString(), 3000, 3000,
                        new RetryOneTime(100));
                c.start();
                c.blockUntilConnected(5, TimeUnit.SECONDS);
                clients.add(c);
                strategies.add(new ZooKeeperStrategy(i, n, c, ForkAssignment.of(ForkAssignment.Rule.RANDOM), "/philo"));
            }
            zk = new DiningTable(strategies, n, DiningTable.fast(Duration.ofMillis(5), Duration.ofMillis(5)));
            zk.runFor(RUN);
            clients.forEach(CuratorFramework::close);
        }

        log.info("COMPARAISON N={} | jetons : {} repas, attente moy {} ms, max {} ms, min/max repas {}/{}, "
                        + "{} messages/repas | ZooKeeper : {} repas, attente moy {} ms, max {} ms, min/max repas {}/{}",
                n, tokens.totalMeals(), fmt(tokens.averageWaitMs()), tokens.maxWaitMs(), min(tokens), max(tokens),
                fmt((double) tokenMessages / tokens.totalMeals()),
                zk.totalMeals(), fmt(zk.averageWaitMs()), zk.maxWaitMs(), min(zk), max(zk));

        assertThat(tokens.violations()).isZero();
        assertThat(zk.violations()).isZero();
        assertThat(min(tokens)).isPositive();
        assertThat(min(zk)).isPositive();
    }

    private static int min(DiningTable t) {
        return Arrays.stream(t.meals()).min().orElse(0);
    }

    private static int max(DiningTable t) {
        return Arrays.stream(t.meals()).max().orElse(0);
    }

    private static String fmt(double d) {
        return String.format("%.1f", d);
    }
}
