package fr.cytech.philo.philosopher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fr.cytech.philo.common.EventType;
import fr.cytech.philo.common.PhilosopherEvent;
import fr.cytech.philo.philosopher.token.PeerDirectory;
import fr.cytech.philo.philosopher.token.TokenNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test d'intégration : 3 vrais microservices philosophes (contextes Spring Boot complets, ports aléatoires)
 * qui s'échangent demandes et jetons en HTTP. Un mini-moniteur HTTP reçoit leurs événements et vérifie
 * qu'aucune fourchette n'est utilisée par deux philosophes à la fois.
 */
class TokenHttpIntegrationTest {

    private static final int N = 3;

    /** Mini-moniteur : mêmes règles que le monitor-service, sans dépendre de ce module. */
    static final class EventCollector {
        final HttpServer server;
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final Map<Integer, List<Integer>> eating = new HashMap<>();
        final List<String> conflicts = new ArrayList<>();
        final int[] meals = new int[N];

        EventCollector() throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/events", exchange -> {
                PhilosopherEvent e = json.readValue(exchange.getRequestBody(), PhilosopherEvent.class);
                record(e);
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            });
            server.start();
        }

        synchronized void record(PhilosopherEvent e) {
            if (e.type() == EventType.EATING) {
                eating.forEach((other, forks) -> e.forks().stream().filter(forks::contains).forEach(f ->
                        conflicts.add("P" + e.philosopherId() + " et P" + other + " sur F" + f)));
                eating.put(e.philosopherId(), e.forks());
            } else if (e.type() == EventType.DONE) {
                eating.remove(e.philosopherId());
                meals[e.philosopherId()]++;
            }
        }

        synchronized int[] meals() {
            return meals.clone();
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }
    }

    private EventCollector collector;
    private final List<ConfigurableApplicationContext> services = new ArrayList<>();
    private final List<String> urls = new ArrayList<>();
    private final RestClient http = RestClient.create();

    @BeforeEach
    void startServices() throws IOException {
        collector = new EventCollector();
        for (int i = 0; i < N; i++) {
            services.add(new SpringApplicationBuilder(PhilosopherApplication.class).run(
                    // arguments de ligne de commande : prioritaires sur application.yml (server.port=8090)
                    "--server.port=0",
                    "--spring.main.banner-mode=off",
                    "--logging.level.root=WARN",
                    "--philo.id=" + i,
                    "--philo.count=" + N,
                    "--philo.mode=TOKEN",
                    "--philo.auto-start=false",
                    "--philo.think-min=0ms", "--philo.think-max=20ms",
                    "--philo.eat-min=5ms", "--philo.eat-max=20ms",
                    "--philo.monitor-url=" + collector.url(),
                    "--philo.token.retransmit-interval=200ms",
                    "--philo.token.retry-delay=50ms"));
        }
        // ports aléatoires : on ne connaît les adresses qu'après démarrage
        services.forEach(ctx -> urls.add("http://localhost:" + ctx.getEnvironment().getProperty("local.server.port")));
        services.forEach(ctx -> ctx.getBean(PeerDirectory.class).update(urls));
    }

    @AfterEach
    void stopServices() {
        services.forEach(ConfigurableApplicationContext::close);
        collector.server.stop(0);
    }

    @Test
    void philosophersShareForksOverHttpWithoutConflict() throws Exception {
        services.forEach(ctx -> ctx.getBean(PhilosopherRunner.class).start());
        Thread.sleep(3000);
        services.forEach(ctx -> ctx.getBean(PhilosopherRunner.class).stop());

        assertThat(collector.conflicts).as("conflits observés").isEmpty();
        assertThat(Arrays.stream(collector.meals()).min().orElseThrow())
                .as("repas du philosophe le moins servi").isGreaterThan(10);
        assertEachTokenHeldExactlyOnce();
    }

    @Test
    void stoppedPhilosopherStillHandsOverItsTokens() throws Exception {
        services.forEach(ctx -> ctx.getBean(PhilosopherRunner.class).start());
        Thread.sleep(1000);
        http.post().uri(urls.get(1) + "/api/stop").retrieve().toBodilessEntity();  // P1 arrête de manger
        int[] before = collector.meals();
        Thread.sleep(1500);
        int[] after = collector.meals();
        services.forEach(ctx -> ctx.getBean(PhilosopherRunner.class).stop());

        // P1 ne mange plus, mais reste joignable : il cède les jetons qu'il détient à qui les demande
        assertThat(after[0] - before[0]).as("repas de P0 après l'arrêt de P1").isGreaterThan(5);
        assertThat(after[2] - before[2]).as("repas de P2 après l'arrêt de P1").isGreaterThan(5);
        assertThat(collector.conflicts).isEmpty();
    }

    @Test
    void tokenStateIsExposedOverHttp() {
        TokenNode.State state = http.get().uri(urls.get(2) + "/api/token/state").retrieve().body(TokenNode.State.class);

        assertThat(state.id()).isEqualTo(2);
        assertThat(state.heldTokens()).containsExactly(2); // au départ P_i détient le jeton de F_i
    }

    private void assertEachTokenHeldExactlyOnce() throws InterruptedException {
        Thread.sleep(300); // laisse finir les derniers transferts
        List<TokenNode.State> states = urls.stream()
                .map(u -> http.get().uri(u + "/api/token/state").retrieve().body(TokenNode.State.class))
                .toList();
        for (int fork = 0; fork < N; fork++) {
            int f = fork;
            long holders = states.stream().filter(s -> s.heldTokens().contains(f)).count();
            assertThat(holders).as("détenteurs du jeton F" + fork).isEqualTo(1);
        }
    }
}
