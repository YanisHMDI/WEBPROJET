package fr.cytech.philo.philosopher.core;

import fr.cytech.philo.common.EventType;
import fr.cytech.philo.common.LamportClock;
import fr.cytech.philo.common.PhilosopherEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PhilosopherTest {

    private static final Philosopher.Timing FAST =
            new Philosopher.Timing(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO);

    @Mock
    private DiningStrategy strategy;
    @Mock
    private EventReporter reporter;

    private Philosopher philosopher;

    @BeforeEach
    void setUp() {
        philosopher = new Philosopher(2, strategy, reporter, new LamportClock(), FAST, d -> { });
    }

    @Test
    void oneCycleEmitsHungryEatingDoneThenReleases() throws Exception {
        philosopher.cycle();

        InOrder order = inOrder(reporter, strategy);
        order.verify(reporter).report(argThat(e -> e.type() == EventType.HUNGRY));
        order.verify(strategy).acquireForks();
        order.verify(reporter).report(argThat(e -> e.type() == EventType.EATING));
        // DONE part AVANT la restitution : sinon le moniteur peut voir un faux conflit
        order.verify(reporter).report(argThat(e -> e.type() == EventType.DONE));
        order.verify(strategy).releaseForks();

        ArgumentCaptor<PhilosopherEvent> events = ArgumentCaptor.forClass(PhilosopherEvent.class);
        verify(reporter, times(3)).report(events.capture());
        List<PhilosopherEvent> sent = events.getAllValues();
        assertThat(sent).extracting(PhilosopherEvent::type)
                .containsExactly(EventType.HUNGRY, EventType.EATING, EventType.DONE);
        assertThat(sent).extracting(PhilosopherEvent::lamport).containsExactly(1L, 2L, 3L);
        assertThat(sent).allMatch(e -> e.philosopherId() == 2);
        assertThat(philosopher.meals()).isEqualTo(1);
    }

    @Test
    void forksAreReleasedEvenIfInterruptedWhileEating() throws Exception {
        Sleeper interruptWhileEating = new Sleeper() {
            int calls;
            @Override
            public void sleep(Duration d) throws InterruptedException {
                if (++calls == 2) {           // 1 = penser, 2 = manger
                    throw new InterruptedException();
                }
            }
        };
        Philosopher p = new Philosopher(0, strategy, reporter, new LamportClock(), FAST, interruptWhileEating);

        assertThatThrownBy(p::cycle).isInstanceOf(InterruptedException.class);
        InOrder order = inOrder(reporter, strategy);
        order.verify(reporter).report(argThat(e -> e.type() == EventType.DONE)); // le moniteur est prévenu…
        order.verify(strategy).releaseForks();                                  // …avant la restitution
        assertThat(p.meals()).isZero();
    }

    @Test
    void interruptionWhileWaitingForForksStopsTheLoopCleanly() throws Exception {
        doThrow(new InterruptedException()).when(strategy).acquireForks();

        philosopher.run(); // ne doit pas boucler à l'infini

        assertThat(philosopher.state()).isEqualTo(Philosopher.State.STOPPED);
        assertThat(philosopher.meals()).isZero();
    }

    @Test
    void randomDurationStaysWithinBounds() {
        for (int i = 0; i < 1000; i++) {
            Duration d = Philosopher.randomBetween(Duration.ofMillis(10), Duration.ofMillis(20));
            assertThat(d.toMillis()).isBetween(10L, 20L);
        }
    }
}
