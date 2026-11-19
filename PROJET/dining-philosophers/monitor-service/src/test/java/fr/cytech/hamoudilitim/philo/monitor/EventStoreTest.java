package fr.cytech.hamoudilitim.philo.monitor;

import fr.cytech.hamoudilitim.philo.common.EventType;
import fr.cytech.hamoudilitim.philo.common.PhilosopherEvent;
import fr.cytech.hamoudilitim.philo.monitor.MonitorModel.PhilosopherState;
import fr.cytech.hamoudilitim.philo.monitor.MonitorModel.RecordedEvent;
import fr.cytech.hamoudilitim.philo.monitor.MonitorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventStoreTest {

    private EventStore store;

    @BeforeEach
    void setUp() {
        store = new EventStore(MonitorProperties.of(5, true, 1000));
    }

    @Test
    void fullCycleUpdatesStateAndMeals() {
        store.record(PhilosopherEvent.of(0, EventType.HUNGRY, 1));
        assertThat(store.snapshot().philosophers().get(0).state()).isEqualTo(PhilosopherState.HUNGRY);

        store.record(PhilosopherEvent.of(0, EventType.EATING, 2));
        store.record(PhilosopherEvent.of(0, EventType.DONE, 3));

        var view = store.snapshot().philosophers().get(0);
        assertThat(view.state()).isEqualTo(PhilosopherState.THINKING);
        assertThat(view.meals()).isEqualTo(1);
        assertThat(view.lastLamport()).isEqualTo(3);
    }

    @Test
    void outOfOrderEventDoesNotOverwriteState() {
        store.record(PhilosopherEvent.of(1, EventType.EATING, 5));
        store.record(PhilosopherEvent.of(1, EventType.HUNGRY, 4)); // arrivé en retard

        var snapshot = store.snapshot();
        assertThat(snapshot.philosophers().get(1).state()).isEqualTo(PhilosopherState.EATING);
        assertThat(snapshot.staleEvents()).isEqualTo(1);
        assertThat(snapshot.totalEvents()).isEqualTo(2);
    }

    @Test
    void historyIsSortedByLamportThenPhilosopher() {
        store.record(PhilosopherEvent.of(2, EventType.HUNGRY, 3));
        store.record(PhilosopherEvent.of(1, EventType.HUNGRY, 3));
        store.record(PhilosopherEvent.of(0, EventType.HUNGRY, 1));

        List<RecordedEvent> history = store.history(10);
        assertThat(history).extracting(r -> r.event().philosopherId()).containsExactly(0, 1, 2);
    }

    @Test
    void neighboursEatingTogetherAreReportedAsConflict() {
        store.record(PhilosopherEvent.of(0, EventType.EATING, 1));
        store.record(PhilosopherEvent.of(1, EventType.EATING, 1));

        var conflicts = store.snapshot().conflicts();
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).sharedFork()).isEqualTo(1);
    }

    @Test
    void nonNeighboursEatingTogetherIsFine() {
        store.record(PhilosopherEvent.of(0, EventType.EATING, 1));
        store.record(PhilosopherEvent.of(2, EventType.EATING, 1));

        assertThat(store.snapshot().conflicts()).isEmpty();
    }

    @Test
    void announcedForksAreUsedInsteadOfTheRoundTable() {
        // partie 3.3 : P0 et P3 ne sont pas voisins, mais partagent F2
        store.record(PhilosopherEvent.of(0, EventType.EATING, 1, List.of(0, 2)));
        store.record(PhilosopherEvent.of(3, EventType.EATING, 1, List.of(3, 2)));

        var conflicts = store.snapshot().conflicts();
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).other()).isZero();
        assertThat(conflicts.get(0).sharedFork()).isEqualTo(2);
    }

    @Test
    void neighboursWithDisjointAnnouncedForksMayEatTogether() {
        store.record(PhilosopherEvent.of(0, EventType.EATING, 1, List.of(0, 3)));
        store.record(PhilosopherEvent.of(1, EventType.EATING, 1, List.of(1, 2)));

        assertThat(store.snapshot().conflicts()).isEmpty();
        assertThat(store.snapshot().philosophers().get(1).forks()).containsExactly(1, 2);
    }

    @Test
    void forksAreForgottenWhenThePhilosopherIsDone() {
        store.record(PhilosopherEvent.of(0, EventType.EATING, 1, List.of(0, 2)));
        store.record(PhilosopherEvent.of(0, EventType.DONE, 2));
        store.record(PhilosopherEvent.of(3, EventType.EATING, 1, List.of(3, 2)));

        assertThat(store.snapshot().conflicts()).isEmpty();
    }

    @Test
    void unknownPhilosopherIsRejected() {
        assertThatThrownBy(() -> store.record(PhilosopherEvent.of(9, EventType.HUNGRY, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void historyIsCapped() {
        EventStore small = new EventStore(MonitorProperties.of(5, false, 3));
        for (int i = 1; i <= 10; i++) {
            small.record(PhilosopherEvent.of(0, EventType.HUNGRY, i));
        }
        assertThat(small.history(100)).hasSize(3);
    }
}
