package fr.cytech.philo.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Horloge logique de Lamport, thread-safe.
 * <ul>
 *   <li>{@link #tick()} : événement local ou envoi de message → h = h + 1 ;</li>
 *   <li>{@link #onReceive(long)} : réception d'un message estampillé t → h = max(h, t) + 1.</li>
 * </ul>
 * Utilisée dès la partie 1 (événements vers le moniteur) et indispensable en partie 3.3
 * (demandes horodatées du protocole à jetons).
 */
public final class LamportClock {

    private final AtomicLong time = new AtomicLong();

    public long tick() {
        return time.incrementAndGet();
    }

    public long onReceive(long receivedTimestamp) {
        if (receivedTimestamp < 0) {
            throw new IllegalArgumentException("horodatage négatif : " + receivedTimestamp);
        }
        return time.updateAndGet(current -> Math.max(current, receivedTimestamp) + 1);
    }

    public long current() {
        return time.get();
    }
}
