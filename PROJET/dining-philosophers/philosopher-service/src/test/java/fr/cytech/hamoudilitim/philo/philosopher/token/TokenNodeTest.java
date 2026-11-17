package fr.cytech.hamoudilitim.philo.philosopher.token;

import fr.cytech.hamoudilitim.philo.common.LamportClock;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenMessages.TokenRelease;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenMessages.TokenRequest;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenMessages.TokenTransfer;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenTransport.ChaosTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests unitaires du protocole à jetons : un seul nœud, transport simulé par Mockito,
 * envois exécutés immédiatement (pas de threads) pour des tests déterministes.
 */
@ExtendWith(MockitoExtension.class)
class TokenNodeTest {

    /** Exécute les envois tout de suite ; garde les réémissions différées pour les lancer à la main. */
    static class DirectDispatcher implements Dispatcher {
        final List<Runnable> later = new ArrayList<>();

        @Override
        public void submit(int peer, Runnable task) {
            task.run();
        }

        @Override
        public void submitLater(int peer, Runnable task, Duration delay) {
            later.add(task);
        }
    }

    @Mock
    private TokenTransport transport;

    private final LamportClock clock = new LamportClock();
    private final DirectDispatcher dispatcher = new DirectDispatcher();
    private TokenNode node; // P0 parmi 3 philosophes, détient initialement le jeton de F0

    @BeforeEach
    void setUp() {
        node = new TokenNode(0, 3, clock, transport, dispatcher, Duration.ofMillis(10));
    }

    private static TokenTransfer token(int fork, long seq, int from, long lamport) {
        return new TokenTransfer(fork, seq, from, 0, lamport, List.of());
    }

    // ------------------------------------------------------------------ demandes horodatées

    @Test
    void requestIsTimestampedAndBroadcastToAllOtherPhilosophers() {
        TokenRequest request = node.requestForks(List.of(0, 1));

        assertThat(request.lamport()).isEqualTo(1);
        assertThat(request.meal()).isEqualTo(1);
        verify(transport).sendRequest(1, request);
        verify(transport).sendRequest(2, request);
        verify(transport, never()).sendRequest(eq(0), any());
    }

    @Test
    void receivingAMessageAppliesLamportRule() {
        node.onRequest(new TokenRequest(1, 1, 10, List.of(1, 2)));

        assertThat(clock.current()).isEqualTo(11); // max(0, 10) + 1
    }

    @Test
    void retransmissionKeepsTheSameTimestamp() {
        TokenRequest request = node.requestForks(List.of(1, 2));
        node.retransmit();

        verify(transport, times(2)).sendRequest(1, request);
        assertThat(node.stats().retransmissions()).isEqualTo(1);
    }

    @Test
    void onlyOneRequestAtATime() {
        node.requestForks(List.of(0, 1));

        assertThatThrownBy(() -> node.requestForks(List.of(0, 2))).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ gestion des jetons

    @Test
    void idleHolderGivesItsTokenToTheRequester() {
        node.onRequest(new TokenRequest(1, 1, 5, List.of(0, 1)));

        verify(transport).sendToken(eq(1), argThat(t -> t.fork() == 0 && t.seq() == 1 && t.from() == 0));
        assertThat(node.holds(0)).isFalse();
    }

    @Test
    void cannotEatWithOnlyOneOfTheTwoTokens() throws Exception {
        node.requestForks(List.of(0, 1));

        assertThat(node.awaitForks(Duration.ofMillis(20))).isFalse();
        assertThat(node.currentForks()).isEmpty();
    }

    @Test
    void eatsOnceBothTokensAreHere() throws Exception {
        node.requestForks(List.of(0, 1));
        node.onToken(token(1, 1, 1, 3));

        assertThat(node.awaitForks(Duration.ofMillis(20))).isTrue();
        assertThat(node.currentForks()).containsExactly(0, 1);
    }

    @Test
    void eatingPhilosopherKeepsItsTokensUntilTheEndOfTheMeal() throws Exception {
        node.requestForks(List.of(0, 1));
        node.onToken(token(1, 1, 1, 3));
        node.awaitForks(Duration.ZERO);

        node.onRequest(new TokenRequest(1, 1, 0, List.of(0, 1))); // pourtant plus prioritaire
        verify(transport, never()).sendToken(anyInt(), any());

        node.releaseRequest();
        verify(transport).sendToken(eq(1), argThat(t -> t.fork() == 0));
        verify(transport).sendToken(eq(1), argThat(t -> t.fork() == 1 && t.seq() == 2));
        verify(transport).sendRelease(eq(1), argThat(r -> r.requester() == 0 && r.meal() == 1));
    }

    @Test
    void hungryPhilosopherYieldsToAMorePriorityRequest() {
        node.requestForks(List.of(0, 1));                           // lamport 1
        node.onRequest(new TokenRequest(2, 1, 0, List.of(0, 2)));   // lamport 0 < 1 : prioritaire

        verify(transport).sendToken(eq(2), argThat(t -> t.fork() == 0));
    }

    @Test
    void equalTimestampsAreBrokenByPhilosopherId() {
        node.requestForks(List.of(0, 1));                           // (1, P0)
        node.onRequest(new TokenRequest(2, 1, 1, List.of(0, 2)));   // (1, P2) : moins prioritaire

        verify(transport, never()).sendToken(anyInt(), any());
        assertThat(node.holds(0)).isTrue();
    }

    @Test
    void atTheEndOfTheMealEachTokenGoesToTheOldestRequestThatNeedsIt() throws Exception {
        node.requestForks(List.of(0, 1));
        node.onToken(token(1, 1, 1, 3));
        node.awaitForks(Duration.ZERO);                              // P0 mange avec F0 et F1

        node.onRequest(new TokenRequest(1, 1, 9, List.of(0, 1)));   // arrivée en premier, mais plus récente
        node.onRequest(new TokenRequest(2, 1, 7, List.of(0, 2)));   // plus ancienne
        assertThat(node.state().queue()).extracting(TokenRequest::requester).containsExactly(0, 2, 1);

        node.releaseRequest();
        verify(transport).sendToken(eq(2), argThat(t -> t.fork() == 0)); // F0 : P2 (7) passe avant P1 (9)
        verify(transport).sendToken(eq(1), argThat(t -> t.fork() == 1)); // F1 : seul P1 la veut
    }

    @Test
    void duplicatedTokenIsIgnored() {
        node.onToken(token(1, 1, 1, 3));
        node.onToken(token(1, 1, 1, 3)); // même transfert réémis (accusé perdu)

        assertThat(node.stats().duplicatesIgnored()).isEqualTo(1);
        assertThat(node.state().heldTokens()).containsExactly(0, 1);
    }

    @Test
    void olderTransferOfATokenThatCameBackIsIgnored() {
        node.onToken(token(1, 5, 2, 3));
        node.onToken(token(1, 4, 1, 3)); // ancien transfert arrivé en retard

        assertThat(node.stats().duplicatesIgnored()).isEqualTo(1);
    }

    @Test
    void unacknowledgedTokenIsResentUntilItArrives() {
        doThrow(new ChaosTransport.MessageLostException("perdu"))
                .doNothing()
                .when(transport).sendToken(eq(1), any());

        node.onRequest(new TokenRequest(1, 1, 5, List.of(0, 1)));
        assertThat(dispatcher.later).hasSize(1);
        assertThat(node.state().inTransit()).containsEntry(0, 1);

        dispatcher.later.remove(0).run();

        ArgumentCaptor<TokenTransfer> sent = ArgumentCaptor.forClass(TokenTransfer.class);
        verify(transport, times(2)).sendToken(eq(1), sent.capture());
        assertThat(sent.getAllValues()).allMatch(t -> t.seq() == 1); // même numéro : pas de second jeton
        assertThat(node.state().inTransit()).isEmpty();
    }

    // ------------------------------------------------------------------ libération et demandes périmées

    @Test
    void releaseRemovesTheRequestFromTheQueue() {
        node.onRequest(new TokenRequest(1, 1, 5, List.of(1, 2)));
        node.onRelease(new TokenRelease(1, 1, 9));

        assertThat(node.state().queue()).isEmpty();
    }

    @Test
    void lateRetransmissionOfAFinishedRequestIsIgnored() {
        node.onRequest(new TokenRequest(1, 1, 5, List.of(1, 2)));
        node.onRelease(new TokenRelease(1, 1, 9));
        node.onRequest(new TokenRequest(1, 1, 5, List.of(1, 2)));

        assertThat(node.stats().staleIgnored()).isEqualTo(1);
        assertThat(node.state().queue()).isEmpty();
    }

    @Test
    void aNewerRequestImpliesThePreviousOneIsFinished() {
        node.onRequest(new TokenRequest(1, 1, 5, List.of(1, 2)));
        node.onRequest(new TokenRequest(1, 2, 8, List.of(1, 2))); // la libération du repas 1 a été perdue

        assertThat(node.state().queue()).extracting(TokenRequest::meal).containsExactly(2L);
    }

    @Test
    void releaseKnowledgeTravelsWithTokens() {
        node.onRequest(new TokenRequest(1, 1, 5, List.of(1, 2)));
        node.onToken(new TokenTransfer(2, 1, 2, 0, 10, List.of(0L, 1L, 0L))); // « P1 a fini son repas 1 »

        assertThat(node.state().queue()).isEmpty();
    }

    @Test
    void lostReleaseIsRetriedAFewTimes() {
        doThrow(new ChaosTransport.MessageLostException("perdu")).when(transport).sendRelease(eq(1), any());
        node.requestForks(List.of(0, 2));
        node.releaseRequest();

        while (!dispatcher.later.isEmpty()) {
            dispatcher.later.remove(0).run();
        }
        verify(transport, times(3)).sendRelease(eq(1), any());
    }

    // ------------------------------------------------------------------ erreurs

    @Test
    void invalidMessagesAreRejected() {
        assertThatThrownBy(() -> node.onRequest(new TokenRequest(1, 1, 1, List.of(1, 7))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> node.onToken(new TokenTransfer(1, 1, 1, 2, 1, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenRequest(1, 1, 1, List.of(2, 2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> node.requestForks(List.of(0, 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
