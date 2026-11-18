package fr.cytech.hamoudilitim.philo.philosopher;

import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMaster.DegreeReport;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMaster.ReachReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partie 5 — test d'intégration : 3 vrais microservices philosophes en HTTP. P0 joue le Master, P1 et P2
 * les Workers. Les philosophes ne mangent pas ici (auto-start=false) : seul le calcul distribué est testé.
 */
class GraphHttpIntegrationTest {

    private static final int N = 3;

    private final List<ConfigurableApplicationContext> services = new ArrayList<>();
    private final List<String> urls = new ArrayList<>();
    private final RestClient http = RestClient.create();

    @BeforeEach
    void startServices() {
        for (int i = 0; i < N; i++) {
            services.add(new SpringApplicationBuilder(PhilosopherApplication.class).run(
                    "--server.port=0",
                    "--spring.main.banner-mode=off",
                    "--logging.level.root=WARN",
                    "--philo.id=" + i,
                    "--philo.count=" + N,
                    "--philo.mode=SPOILED",
                    "--philo.auto-start=false",
                    "--philo.graph.task-timeout=2s"));
        }
        services.forEach(ctx -> urls.add("http://localhost:" + ctx.getEnvironment().getProperty("local.server.port")));
        services.forEach(ctx -> ctx.getBean(PeerDirectory.class).update(urls));
    }

    @AfterEach
    void stopServices() {
        services.forEach(ctx -> {
            if (ctx.isActive()) {
                ctx.close();
            }
        });
    }

    private <T> T master(String path, Map<String, Object> job, Class<T> type) {
        return http.post().uri(urls.get(0) + "/api/master/" + path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(job)
                .retrieve()
                .body(type);
    }

    @Test
    void degreesAreComputedByTheWorkersOverHttp() {
        DegreeReport r = master("degrees", Map.of("nodes", 5_000, "edges", 20_000, "seed", 1), DegreeReport.class);

        assertThat(r.correct()).isTrue();
        assertThat(r.workers()).containsExactly(1, 2);
        assertThat(r.sumOfDegrees()).isEqualTo(40_000);
    }

    @Test
    void reachabilityIsComputedByTheWorkersOverHttp() {
        ReachReport r = master("reachability",
                Map.of("nodes", 2_000, "edges", 4_000, "seed", 2, "source", 0, "target", 1_500), ReachReport.class);

        assertThat(r.correct()).isTrue();
    }

    @Test
    void theMasterCarriesOnWhenAWorkerIsShutDown() {
        services.get(2).close();                               // P2 s'arrête : connexion refusée

        DegreeReport degrees = master("degrees", Map.of("nodes", 2_000, "edges", 8_000, "seed", 3), DegreeReport.class);
        ReachReport reach = master("reachability",
                Map.of("nodes", 2_000, "edges", 4_000, "seed", 4, "source", 0, "target", 1_000), ReachReport.class);

        assertThat(degrees.correct()).isTrue();
        assertThat(degrees.reassigned()).as("la partition de P2 est passée à P1").isGreaterThanOrEqualTo(1);
        assertThat(reach.correct()).isTrue();
    }
}
