package fr.cytech.philo.supervisor;

import fr.cytech.philo.supervisor.fork.ForkTable;
import fr.cytech.philo.supervisor.fork.LockedPairStrategy;
import fr.cytech.philo.supervisor.fork.NaiveForkService;
import fr.cytech.philo.supervisor.fork.PairStrategy;
import fr.cytech.philo.supervisor.fork.RaceWindow;
import fr.cytech.philo.supervisor.fork.UnsafePairStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SupervisorConfig {

    @Bean
    ForkTable forkTable(SupervisorProperties properties) {
        return new ForkTable(properties.forks());
    }

    @Bean
    RaceWindow raceWindow(SupervisorProperties properties) {
        return new RaceWindow(properties.raceWindow());
    }

    @Bean
    NaiveForkService naiveForkService(ForkTable table, RaceWindow raceWindow) {
        return new NaiveForkService(table, raceWindow);
    }

    @Bean
    PairStrategy pairStrategy(SupervisorProperties properties, ForkTable table, RaceWindow raceWindow) {
        return switch (properties.pairMode()) {
            case UNSAFE -> new UnsafePairStrategy(table, raceWindow);
            case LOCKED -> new LockedPairStrategy(table, raceWindow);
        };
    }
}
