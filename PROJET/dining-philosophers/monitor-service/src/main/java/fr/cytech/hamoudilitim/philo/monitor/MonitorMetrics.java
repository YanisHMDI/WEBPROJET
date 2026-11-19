package fr.cytech.hamoudilitim.philo.monitor;

import fr.cytech.hamoudilitim.philo.monitor.MonitorModel.DashboardSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Partie 6 — supervision : expose l'état du dîner à Prometheus ({@code /actuator/prometheus}).
 * <ul>
 *   <li>{@code monitor_conflicts} : violations de l'exclusion mutuelle observées (doit rester à 0) ;</li>
 *   <li>{@code monitor_events} et {@code monitor_events_stale} : événements reçus, dont arrivés hors ordre ;</li>
 *   <li>{@code monitor_meals{philosopher="i"}} : repas de chaque philosophe (équité, famine).</li>
 * </ul>
 */
@Component
public class MonitorMetrics implements MeterBinder {

    private final EventStore store;
    private final MonitorProperties props;

    public MonitorMetrics(EventStore store, MonitorProperties props) {
        this.store = store;
        this.props = props;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("monitor.conflicts", store, s -> s.snapshot().conflicts().size())
                .description("violations de l'exclusion mutuelle observées").register(registry);
        Gauge.builder("monitor.events", store, s -> s.snapshot().totalEvents())
                .description("événements reçus").register(registry);
        Gauge.builder("monitor.events.stale", store, s -> s.snapshot().staleEvents())
                .description("événements arrivés hors ordre").register(registry);
        for (int i = 0; i < props.philosophers(); i++) {
            int id = i;
            Gauge.builder("monitor.meals", store, s -> meals(s.snapshot(), id))
                    .tag("philosopher", String.valueOf(id))
                    .description("repas observés").register(registry);
        }
    }

    private static double meals(DashboardSnapshot snapshot, int id) {
        return id < snapshot.philosophers().size() ? snapshot.philosophers().get(id).meals() : 0;
    }
}
