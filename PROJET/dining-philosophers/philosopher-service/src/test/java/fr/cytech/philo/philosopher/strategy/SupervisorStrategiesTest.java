package fr.cytech.philo.philosopher.strategy;

import fr.cytech.philo.philosopher.client.SupervisorClient;
import fr.cytech.philo.philosopher.core.Sleeper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupervisorStrategiesTest {

    private static final Duration RETRY = Duration.ofMillis(100);

    @Mock
    private SupervisorClient supervisor;
    @Mock
    private Sleeper sleeper;

    @Test
    void naiveTakesLeftThenRightAndRetriesWhileBusy() throws Exception {
        // P4 sur 5 : gauche = F4, droite = F0
        when(supervisor.isAvailable(4)).thenReturn(true);
        when(supervisor.acquireFork(4, 4)).thenReturn(true);
        when(supervisor.isAvailable(0)).thenReturn(false, false, true);
        when(supervisor.acquireFork(0, 4)).thenReturn(true);

        new NaiveSupervisorStrategy(4, 5, supervisor, sleeper, RETRY).acquireForks();

        InOrder order = inOrder(supervisor);
        order.verify(supervisor).acquireFork(4, 4);
        order.verify(supervisor).acquireFork(0, 4);
        verify(sleeper, times(2)).sleep(RETRY);
    }

    @Test
    void naiveRetriesWhenAvailableButGrantRefused() throws Exception {
        when(supervisor.isAvailable(anyInt())).thenReturn(true);
        when(supervisor.acquireFork(1, 1)).thenReturn(false, true);   // quelqu'un est passé avant
        when(supervisor.acquireFork(2, 1)).thenReturn(true);

        new NaiveSupervisorStrategy(1, 5, supervisor, sleeper, RETRY).acquireForks();

        verify(supervisor, times(2)).acquireFork(1, 1);
        verify(sleeper, times(1)).sleep(RETRY);
    }

    @Test
    void naiveReleasesBothForks() {
        new NaiveSupervisorStrategy(1, 5, supervisor, sleeper, RETRY).releaseForks();

        verify(supervisor).releaseFork(1, 1);
        verify(supervisor).releaseFork(2, 1);
    }

    @Test
    void pairRetriesUntilGranted() throws Exception {
        when(supervisor.acquirePair(3)).thenReturn(false, false, false, true);

        new PairSupervisorStrategy(3, supervisor, sleeper, RETRY).acquireForks();

        verify(supervisor, times(4)).acquirePair(3);
        verify(sleeper, times(3)).sleep(RETRY);
    }
}
