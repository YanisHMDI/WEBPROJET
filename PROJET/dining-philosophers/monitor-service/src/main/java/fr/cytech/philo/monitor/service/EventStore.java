package fr.cytech.philo.monitor.service;

import fr.cytech.philo.common.EventType;
import fr.cytech.philo.common.ForkTopology;
import fr.cytech.philo.common.PhilosopherEvent;
import fr.cytech.philo.monitor.MonitorProperties;
import fr.cytech.philo.monitor.model.Conflict;
import fr.cytech.philo.monitor.model.DashboardSnapshot;
import fr.cytech.philo.monitor.model.PhilosopherState;
import fr.cytech.philo.monitor.model.PhilosopherView;
import fr.cytech.philo.monitor.model.RecordedEvent;
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
        views[id] = new PhilosopherView(id, next, meals, event.lamport(), now);

        if (next == PhilosopherState.EATING && properties.detectNeighbourConflicts()) {
            detectConflicts(id, recorded.sequence(), now);
        }
        return recorded;
    }

    private void detectConflicts(int id, long seq, Instant now) {
        int n = views.length;
        int left = ForkTopology.leftNeighbour(id, n);
        int right = ForkTopology.rightNeighbour(id, n);
        if (views[left].state() == PhilosopherState.EATING) {
            conflicts.add(new Conflict(seq, id, left, ForkTopology.leftFork(id, n), now));
        }
        if (right != left && views[right].state() == PhilosopherState.EATING) {
            conflicts.add(new Conflict(seq, id, right, ForkTopology.rightFork(id, n), now));
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
            views[i] = new PhilosopherView(i, PhilosopherState.UNKNOWN, 0, 0, null);
        }
    }
}
