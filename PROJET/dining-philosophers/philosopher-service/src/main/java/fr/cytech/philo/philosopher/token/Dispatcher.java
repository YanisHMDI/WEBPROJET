package fr.cytech.philo.philosopher.token;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Exécute les envois réseau hors du verrou du {@link TokenNode}.
 * <p>
 * Le nœud ne fait jamais d'appel HTTP en tenant son verrou : sinon deux philosophes qui s'envoient un
 * message en même temps attendraient chacun la réponse de l'autre (interblocage distribué).
 */
public interface Dispatcher {

    void submit(int peer, Runnable task);

    void submitLater(int peer, Runnable task, Duration delay);

    /**
     * Un fil d'envoi par destinataire : les messages vers un même pair partent dans l'ordre (FIFO) et
     * un pair lent ou en panne ne retarde que sa propre file, pas les messages vers les autres.
     */
    public class PeerDispatcher implements Dispatcher, AutoCloseable {

        private final ScheduledExecutorService[] queues;

        public PeerDispatcher(int ownerId, int count) {
            queues = new ScheduledExecutorService[count];
            for (int peer = 0; peer < count; peer++) {
                String name = "token-P" + ownerId + "->P" + peer;
                queues[peer] = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, name);
                    t.setDaemon(true);
                    return t;
                });
            }
        }

        @Override
        public void submit(int peer, Runnable task) {
            if (!queues[peer].isShutdown()) {
                queues[peer].execute(task);
            }
        }

        @Override
        public void submitLater(int peer, Runnable task, Duration delay) {
            if (!queues[peer].isShutdown()) {
                queues[peer].schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        @Override
        public void close() {
            for (ScheduledExecutorService q : queues) {
                q.shutdownNow();
            }
        }
    }
}
