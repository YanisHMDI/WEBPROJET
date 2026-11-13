package fr.cytech.philo.monitor.model;

import java.util.List;

public record DashboardSnapshot(
        int philosopherCount,
        long totalEvents,
        long staleEvents,
        List<PhilosopherView> philosophers,
        List<Conflict> conflicts) {
}
