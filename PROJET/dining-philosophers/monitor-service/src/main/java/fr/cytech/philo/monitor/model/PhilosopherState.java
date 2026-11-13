package fr.cytech.philo.monitor.model;

import fr.cytech.philo.common.EventType;

public enum PhilosopherState {
    UNKNOWN,
    THINKING,
    HUNGRY,
    EATING;

    public static PhilosopherState after(EventType type) {
        return switch (type) {
            case HUNGRY -> HUNGRY;
            case EATING -> EATING;
            case DONE -> THINKING;
        };
    }
}
