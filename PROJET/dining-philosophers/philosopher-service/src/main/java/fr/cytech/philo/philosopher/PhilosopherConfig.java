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
    DiningStrategy diningStrategy(PhilosopherProperties props, SupervisorClient supervisor, Sleeper sleeper) {
        return switch (props.mode()) {
            case SPOILED -> new SpoiledStrategy();
            case NAIVE_SUPERVISOR -> new NaiveSupervisorStrategy(
                    props.id(), props.count(), supervisor, sleeper, props.retryDelay());
            case PAIR_SUPERVISOR -> new PairSupervisorStrategy(
                    props.id(), supervisor, sleeper, props.retryDelay());
        };
    }

    @Bean
    Philosopher philosopher(PhilosopherProperties props, DiningStrategy strategy, EventReporter reporter,
                            LamportClock clock, Sleeper sleeper) {
        var timing = new Philosopher.Timing(props.thinkMin(), props.thinkMax(), props.eatMin(), props.eatMax());
        return new Philosopher(props.id(), strategy, reporter, clock, timing, sleeper);
    }

    private static RestClient restClient(String baseUrl, PhilosopherProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        int timeout = (int) props.httpTimeout().toMillis();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
