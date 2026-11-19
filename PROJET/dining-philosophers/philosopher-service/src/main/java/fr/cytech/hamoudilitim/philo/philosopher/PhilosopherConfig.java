package fr.cytech.hamoudilitim.philo.philosopher;

import fr.cytech.hamoudilitim.philo.common.LamportClock;
import fr.cytech.hamoudilitim.philo.common.PhilosopherEvent;
import fr.cytech.hamoudilitim.philo.philosopher.core.DiningStrategy;
import fr.cytech.hamoudilitim.philo.philosopher.core.EventReporter;
import fr.cytech.hamoudilitim.philo.philosopher.core.ForkAssignment;
import fr.cytech.hamoudilitim.philo.philosopher.core.HttpEventReporter;
import fr.cytech.hamoudilitim.philo.philosopher.core.KafkaEventReporter;
import fr.cytech.hamoudilitim.philo.philosopher.core.MeteredStrategy;
import fr.cytech.hamoudilitim.philo.philosopher.core.Philosopher;
import fr.cytech.hamoudilitim.philo.philosopher.core.Sleeper;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMaster;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphWorker;
import fr.cytech.hamoudilitim.philo.philosopher.graph.WorkerClient;
import fr.cytech.hamoudilitim.philo.philosopher.strategy.NaiveSupervisorStrategy;
import fr.cytech.hamoudilitim.philo.philosopher.strategy.PairSupervisorStrategy;
import fr.cytech.hamoudilitim.philo.philosopher.strategy.SpoiledStrategy;
import fr.cytech.hamoudilitim.philo.philosopher.strategy.SupervisorClient;
import fr.cytech.hamoudilitim.philo.philosopher.token.Dispatcher.PeerDispatcher;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenNode;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenStrategy;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenTransport;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenTransport.ChaosTransport;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenTransport.HttpTokenTransport;
import fr.cytech.hamoudilitim.philo.philosopher.zookeeper.ZooKeeperStrategy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class PhilosopherConfig {

    @Bean
    LamportClock lamportClock() {
        return new LamportClock();
    }

    @Bean
    Sleeper sleeper() {
        return Sleeper.REAL;
    }

    @Bean
    PeerDirectory peerDirectory(PhilosopherProperties props) {
        return new PeerDirectory(props.peers());
    }

    @Bean
    EventReporter eventReporter(PhilosopherProperties props,
                                ObjectProvider<KafkaTemplate<String, PhilosopherEvent>> kafka) {
        return switch (props.events()) {
            case HTTP -> new HttpEventReporter(restClient(props.monitorUrl(), props.httpTimeout()));
            case KAFKA -> new KafkaEventReporter(kafka.getObject());
        };
    }

    @Bean
    SupervisorClient supervisorClient(PhilosopherProperties props) {
        return new SupervisorClient(restClient(props.supervisorUrl(), props.httpTimeout()));
    }

    /** Choisit la façon d'obtenir les fourchettes selon la partie du sujet ({@code philo.mode}). */
    @Bean
    DiningStrategy diningStrategy(PhilosopherProperties props, SupervisorClient supervisor, Sleeper sleeper,
                                  ObjectProvider<TokenNode> tokenNode, ObjectProvider<CuratorFramework> zookeeper,
                                  MeterRegistry metrics) {
        // les mesures (attente, repas) sont ajoutées autour de la stratégie, sans la modifier
        return new MeteredStrategy(strategyFor(props, supervisor, sleeper, tokenNode, zookeeper), metrics,
                props.id(), props.mode().name());
    }

    private static DiningStrategy strategyFor(PhilosopherProperties props, SupervisorClient supervisor,
                                              Sleeper sleeper, ObjectProvider<TokenNode> tokenNode,
                                              ObjectProvider<CuratorFramework> zookeeper) {
        ForkAssignment assignment = ForkAssignment.of(props.assignment());
        return switch (props.mode()) {
            case SPOILED -> new SpoiledStrategy();
            case NAIVE_SUPERVISOR -> new NaiveSupervisorStrategy(
                    props.id(), props.count(), supervisor, sleeper, props.retryDelay());
            case PAIR_SUPERVISOR -> new PairSupervisorStrategy(
                    props.id(), supervisor, sleeper, props.retryDelay());
            case TOKEN -> new TokenStrategy(tokenNode.getObject(), assignment, props.count(),
                    props.token().retransmitInterval(), props.token().giveUpAfter());
            case ZOOKEEPER -> new ZooKeeperStrategy(props.id(), props.count(), zookeeper.getObject(), assignment,
                    props.zookeeper().root());
        };
    }

    @Bean
    Philosopher philosopher(PhilosopherProperties props, DiningStrategy strategy, EventReporter reporter,
                            LamportClock clock, Sleeper sleeper) {
        var timing = new Philosopher.Timing(props.thinkMin(), props.thinkMax(), props.eatMin(), props.eatMax());
        return new Philosopher(props.id(), strategy, reporter, clock, timing, sleeper);
    }

    // ------------------------------------------------------------------ partie 5 : Master–Worker (tous modes)

    @Bean
    GraphWorker graphWorker(PhilosopherProperties props) {
        return new GraphWorker(props.id(), props.graph().workerDelay());
    }

    @Bean(destroyMethod = "close")
    GraphMaster graphMaster(PhilosopherProperties props, PeerDirectory peers) {
        // délai HTTP large : un gros graphe peut mettre du temps à transférer ; c'est taskTimeout qui décide
        // quand relancer une tâche ailleurs
        RestClient http = restClient(null, Duration.ofSeconds(60));
        return new GraphMaster(new WorkerClient.HttpWorkerClient(http, peers), props.graph().taskTimeout());
    }

    static RestClient restClient(String baseUrl, Duration timeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Math.min(timeout.toMillis(), 2000));
        factory.setReadTimeout((int) timeout.toMillis());
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ partie 3.3 : protocole à jetons

    @Configuration
    @ConditionalOnProperty(name = "philo.mode", havingValue = "TOKEN")
    public static class TokenConfig {

        @Bean(destroyMethod = "close")
        PeerDispatcher tokenDispatcher(PhilosopherProperties props) {
            return new PeerDispatcher(props.id(), props.count());
        }

        @Bean
        TokenTransport tokenTransport(PhilosopherProperties props, PeerDirectory peers) {
            TokenTransport http = new HttpTokenTransport(restClient(null, props.httpTimeout()), peers);
            var t = props.token();
            if (t.dropRate() > 0 || !t.maxDelay().isZero()) {
                return new ChaosTransport(http, t.dropRate(), t.maxDelay());
            }
            return http;
        }

        @Bean
        TokenNode tokenNode(PhilosopherProperties props, LamportClock clock, TokenTransport transport,
                            PeerDispatcher dispatcher) {
            return new TokenNode(props.id(), props.count(), clock, transport, dispatcher, props.token().retryDelay());
        }
    }

    // ------------------------------------------------------------------ partie 3.4 : ZooKeeper

    @Configuration
    @ConditionalOnProperty(name = "philo.mode", havingValue = "ZOOKEEPER")
    public static class ZooKeeperConfig {

        @Bean(initMethod = "start", destroyMethod = "close")
        CuratorFramework zookeeperClient(PhilosopherProperties props) {
            var zk = props.zookeeper();
            return CuratorFrameworkFactory.builder()
                    .connectString(zk.connect())
                    .sessionTimeoutMs((int) zk.sessionTimeout().toMillis())
                    .connectionTimeoutMs(5000)
                    .retryPolicy(new ExponentialBackoffRetry(200, 5))
                    .build();
        }
    }
}
