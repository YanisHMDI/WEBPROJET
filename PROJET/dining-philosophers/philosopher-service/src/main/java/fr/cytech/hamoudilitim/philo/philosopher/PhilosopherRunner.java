package fr.cytech.hamoudilitim.philo.philosopher;

import fr.cytech.hamoudilitim.philo.philosopher.core.Philosopher;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fait tourner la boucle du philosophe dans un thread dédié (hors threads HTTP).
 */
@Component
public class PhilosopherRunner {

    private final Philosopher philosopher;
    private final PhilosopherProperties props;
    private Thread thread;

    public PhilosopherRunner(Philosopher philosopher, PhilosopherProperties props) {
        this.philosopher = philosopher;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (props.autoStart()) {
            start();
        }
    }

    public synchronized boolean start() {
        if (thread != null && thread.isAlive()) {
            return false;
        }
        thread = new Thread(philosopher, "philosopher-" + props.id());
        thread.start();
        return true;
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    public synchronized boolean stop() {
        if (thread == null || !thread.isAlive()) {
            return false;
        }
        philosopher.stop();
        thread.interrupt();
        return true;
    }
}
