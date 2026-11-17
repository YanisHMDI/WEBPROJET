package fr.cytech.philo.philosopher;

import fr.cytech.philo.common.LamportClock;
import fr.cytech.philo.philosopher.client.HttpEventReporter;
import fr.cytech.philo.philosopher.client.SupervisorClient;
import fr.cytech.philo.philosopher.core.DiningStrategy;
import fr.cytech.philo.philosopher.core.EventReporter;
import fr.cytech.philo.philosopher.core.Philosopher;
import fr.cytech.philo.philosopher.core.Sleeper;
import fr.cytech.philo.philosopher.strategy.NaiveSupervisorStrategy;
import fr.cytech.philo.philosopher.strategy.PairSupervisorStrategy;
import fr.cytech.philo.philosopher.strategy.SpoiledStrategy;
import fr.cytech.philo.philosopher.token.ForkAssignment;
import fr.cytech.philo.philosopher.token.TokenNode;
import fr.cytech.philo.philosopher.token.TokenStrategy;
import org.springframework.beans.factory.ObjectProvider;
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
}
