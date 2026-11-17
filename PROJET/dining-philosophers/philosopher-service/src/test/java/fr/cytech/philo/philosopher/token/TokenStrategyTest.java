package fr.cytech.philo.philosopher.token;

import fr.cytech.philo.philosopher.token.TokenStrategy.ForkAssignment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenStrategyTest {

    @Mock
    private TokenNode node;

    private TokenStrategy strategy() {
        when(node.id()).thenReturn(1);
        return new TokenStrategy(node, ForkAssignment.neighbours(), 5, Duration.ofMillis(10));
    }

    @Test
    void requestsTheAssignedForksThenRetransmitsWhileWaiting() throws Exception {
        TokenStrategy strategy = strategy();
        when(node.awaitForks(any())).thenReturn(false, false, true);

        strategy.acquireForks();

        InOrder order = inOrder(node);
        order.verify(node).requestForks(List.of(1, 2));
        order.verify(node, times(2)).retransmit();
        verify(node, never()).releaseRequest();
    }

    @Test
    void interruptedWhileWaitingAbandonsTheRequest() throws Exception {
        TokenStrategy strategy = strategy();
        when(node.awaitForks(any())).thenThrow(new InterruptedException());

        assertThatThrownBy(strategy::acquireForks).isInstanceOf(InterruptedException.class);
        verify(node).releaseRequest();
    }
}
