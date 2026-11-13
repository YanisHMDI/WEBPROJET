package fr.cytech.philo.supervisor.fork;

import java.time.Duration;

/**
 * Pause volontaire placée entre la vérification et la réservation d'une fourchette.
 * Avec 0 ms le code est "normal" ; avec quelques ms, les entrelacements dangereux
 * deviennent quasi systématiques, ce qui rend les failles démontrables par des tests.
 */
public final class RaceWindow {

    public static final RaceWindow NONE = new RaceWindow(Duration.ZERO);

    private final Duration duration;

    public RaceWindow(Duration duration) {
        this.duration = duration == null ? Duration.ZERO : duration;
    }

    public void pause() {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
