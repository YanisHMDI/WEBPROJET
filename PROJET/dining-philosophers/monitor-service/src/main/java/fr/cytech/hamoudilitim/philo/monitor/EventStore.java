package fr.cytech.hamoudilitim.philo.monitor;

import fr.cytech.hamoudilitim.philo.common.EventType;
import fr.cytech.hamoudilitim.philo.common.ForkTopology;
import fr.cytech.hamoudilitim.philo.common.PhilosopherEvent;
import fr.cytech.hamoudilitim.philo.monitor.MonitorModel.Conflict;
import fr.cytech.hamoudilitim.philo.monitor.MonitorModel.DashboardSnapshot;
import fr.cytech.hamoudilitim.philo.monitor.MonitorModel.PhilosopherState;
import fr.cytech.hamoudilitim.philo.monitor.MonitorModel.PhilosopherView;
import fr.cytech.hamoudilitim.philo.monitor.MonitorModel.RecordedEvent;
import fr.cytech.hamoudilitim.philo.monitor.MonitorProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Stocke les événements reçus et reconstruit l'état de chaque philosophe.
 * <p>
 * Exclusion mutuelle : quand un philosophe passe EATING, on vérifie qu'aucun autre philosophe en train
 * de manger ne tient une de ses fourchettes. Cette détection est sans faux positif car chaque philosophe
 * envoie (de façon synchrone) DONE avant de rendre ses fourchettes ; elle peut en revanche manquer un
 * chevauchement très court si un EATING arrive en retard : c'est un observateur, pas une preuve.
 * <p>
 * Les messages HTTP peuvent arriver dans le désordre : pour un même philosophe, un événement
 * dont l'horloge de Lamport est inférieure ou égale à la dernière vue est conservé dans
 * l'historique mais n'écrase pas l'état courant (événement "périmé").
 * <p>
 * L'historique est restitué trié par (lamport, philosopherId), l'ordre total classique de Lamport.
 */
@Service
public class EventStore {

    private static final Comparator<RecordedEvent> LAMPORT_ORDER = Comparator
            .comparingLong((RecordedEvent r) -> r.event().lamport())
            .thenComparingInt(r -> r.event().philosopherId());

    private final MonitorProperties properties;
    private final Clock clock;

    private final Deque<RecordedEvent> history = new ArrayDeque<>();
    private final List<Conflict> conflicts = new ArrayList<>();
    private PhilosopherView[] views;
    private long sequence;
    private long staleEvents;

    @Autowired
    public EventStore(MonitorProperties properties) {
        this(properties, Clock.systemUTC());
    }

    EventStore(MonitorProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        reset();
    }

    public synchronized RecordedEvent record(PhilosopherEvent event) {
        int id = event.philosopherId();
        if (id >= views.length) {
            throw new IllegalArgumentException(
                    "philosophe " + id + " inconnu (le moniteur est configuré pour " + views.length + ")");
        }
        Instant now = clock.instant();
        RecordedEvent recorded = new RecordedEvent(++sequence, event, now);
        history.addLast(recorded);
        while (history.size() > properties.maxEvents()) {
            history.removeFirst();
        }

        PhilosopherView previous = views[id];
        if (event.lamport() <= previous.lastLamport()) {
            staleEvents++;
            return recorded;
        }

        PhilosopherState next = PhilosopherState.after(event.type());
        int meals = previous.meals() + (event.type() == EventType.DONE ? 1 : 0);
        List<Integer> forks = next == PhilosopherState.EATING ? forksOf(event) : List.of();
        views[id] = new PhilosopherView(id, next, meals, event.lamport(), now, forks);

        if (next == PhilosopherState.EATING && properties.detectNeighbourConflicts()) {
            detectConflicts(id, forks, recorded.sequence(), now);
        }
        return recorded;
    }

    /** Fourchettes annoncées par le philosophe, ou disposition classique F_i / F_{i+1} à défaut. */
    private List<Integer> forksOf(PhilosopherEvent event) {
        if (!event.forks().isEmpty()) {
            return event.forks();
        }
        int id = event.philosopherId();
        return List.of(ForkTopology.leftFork(id, views.length), ForkTopology.rightFork(id, views.length));
    }

    /** Conflit = un autre philosophe mange déjà avec une des fourchettes que {@code id} vient de prendre. */
    private void detectConflicts(int id, List<Integer> forks, long seq, Instant now) {
        for (PhilosopherView other : views) {
            if (other.id() == id || other.state() != PhilosopherState.EATING) {
                continue;
            }
            for (Integer fork : forks) {
                if (other.forks().contains(fork)) {
                    conflicts.add(new Conflict(seq, id, other.id(), fork, now));
                }
            }
        }
    }

    /** Derniers événements, triés selon l'ordre total de Lamport. */
    public synchronized List<RecordedEvent> history(int limit) {
        List<RecordedEvent> sorted = new ArrayList<>(history);
        sorted.sort(LAMPORT_ORDER);
        int from = Math.max(0, sorted.size() - Math.max(limit, 0));
        return List.copyOf(sorted.subList(from, sorted.size()));
    }

    public synchronized DashboardSnapshot snapshot() {
        return new DashboardSnapshot(views.length, sequence, staleEvents, List.of(views), List.copyOf(conflicts));
    }

    public synchronized void reset() {
        int n = properties.philosophers();
        if (n < 2) {
            throw new IllegalStateException("monitor.philosophers doit être >= 2");
        }
        history.clear();
        conflicts.clear();
        sequence = 0;
        staleEvents = 0;
        views = new PhilosopherView[n];
        for (int i = 0; i < n; i++) {
            views[i] = new PhilosopherView(i, PhilosopherState.UNKNOWN, 0, 0, null, List.of());
        }
    }
}
