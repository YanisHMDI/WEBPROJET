package fr.cytech.philo.philosopher;

import fr.cytech.philo.common.LamportClock;
import fr.cytech.philo.philosopher.core.DiningStrategy;
import fr.cytech.philo.philosopher.core.EventReporter;
import fr.cytech.philo.philosopher.core.HttpEventReporter;
import fr.cytech.philo.philosopher.core.Philosopher;
import fr.cytech.philo.philosopher.core.Sleeper;
import fr.cytech.philo.philosopher.strategy.NaiveSupervisorStrategy;
import fr.cytech.philo.philosopher.strategy.PairSupervisorStrategy;
import fr.cytech.philo.philosopher.strategy.SpoiledStrategy;
import fr.cytech.philo.philosopher.strategy.SupervisorClient;
import fr.cytech.philo.philosopher.token.Dispatcher.PeerDispatcher;
import fr.cytech.philo.philosopher.token.TokenNode;
import fr.cytech.philo.philosopher.token.TokenStrategy.ForkAssignment;
import fr.cytech.philo.philosopher.token.TokenStrategy;
import fr.cytech.philo.philosopher.token.TokenTransport.ChaosTransport;
import fr.cytech.philo.philosopher.token.TokenTransport.HttpTokenTransport;
import fr.cytech.philo.philosopher.token.TokenTransport.PeerDirectory;
import fr.cytech.philo.philosopher.token.TokenTransport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

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
    EventReporter eventReporter(PhilosopherProperties props) {
        return new HttpEventReporter(restClient(props.monitorUrl(), props));
    }

    @Bean
    SupervisorClient supervisorClient(PhilosopherProperties props) {
        return new SupervisorClient(restClient(props.supervisorUrl(), props));
    }

    @Bean
    DiningStrategy diningStrategy(PhilosopherProperties props, SupervisorClient supervisor, Sleeper sleeper,
                                  ObjectProvider<TokenNode> tokenNode) {
        return switch (props.mode()) {
            case SPOILED -> new SpoiledStrategy();
            case NAIVE_SUPERVISOR -> new NaiveSupervisorStrategy(
                    props.id(), props.count(), supervisor, sleeper, props.retryDelay());
            case PAIR_SUPERVISOR -> new PairSupervisorStrategy(
                    props.id(), supervisor, sleeper, props.retryDelay());
            case TOKEN -> new TokenStrategy(tokenNode.getObject(), ForkAssignment.of(props.token().assignment()),
                    props.count(), props.token().retransmitInterval());
        };
    }

    @Bean
    Philosopher philosopher(PhilosopherProperties props, DiningStrategy strategy, EventReporter reporter,
                            LamportClock clock, Sleeper sleeper) {
        var timing = new Philosopher.Timing(props.thinkMin(), props.thinkMax(), props.eatMin(), props.eatMax());
        return new Philosopher(props.id(), strategy, reporter, clock, timing, sleeper);
    }

    static RestClient restClient(String baseUrl, PhilosopherProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        int timeout = (int) props.httpTimeout().toMillis();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    /**
     * Beans du protocole à jetons, créés uniquement en mode TOKEN.
     */
    @Configuration
    @ConditionalOnProperty(name = "philo.mode", havingValue = "TOKEN")
    public static class TokenConfig {

        @Bean
        PeerDirectory peerDirectory(PhilosopherProperties props) {
            var peers = props.token().peers();
            if (!peers.isEmpty() && peers.size() != props.count()) {
                throw new IllegalArgumentException("philo.token.peers doit lister " + props.count()
                        + " adresses (une par philosophe), reçu " + peers.size());
            }
            return new PeerDirectory(peers);
        }

        @Bean(destroyMethod = "close")
        PeerDispatcher tokenDispatcher(PhilosopherProperties props) {
            return new PeerDispatcher(props.id(), props.count());
        }

        @Bean
        TokenTransport tokenTransport(PhilosopherProperties props, PeerDirectory peers) {
            TokenTransport http = new HttpTokenTransport(PhilosopherConfig.restClient(null, props), peers);
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
}
