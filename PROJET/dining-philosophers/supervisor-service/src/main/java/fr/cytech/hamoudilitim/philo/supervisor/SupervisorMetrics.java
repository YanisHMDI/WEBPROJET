package fr.cytech.hamoudilitim.philo.supervisor;

import fr.cytech.hamoudilitim.philo.supervisor.fork.ForkTable;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Partie 6 — supervision du superviseur : fourchettes prises et violations détectées (doit rester à 0 en LOCKED).
 */
@Component
public class SupervisorMetrics implements MeterBinder {

    private final ForkTable table;

    public SupervisorMetrics(ForkTable table) {
        this.table = table;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("supervisor.violations", table, ForkTable::violations)
                .description("fourchettes attribuées à deux philosophes").register(registry);
        Gauge.builder("supervisor.forks.taken", table, t -> t.snapshot().stream().filter(Objects::nonNull).count())
                .description("fourchettes actuellement attribuées").register(registry);
    }
}
