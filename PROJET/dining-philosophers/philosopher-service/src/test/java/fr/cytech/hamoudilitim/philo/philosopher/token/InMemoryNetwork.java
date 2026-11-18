package fr.cytech.hamoudilitim.philo.philosopher.token;

import fr.cytech.hamoudilitim.philo.common.LamportClock;
import fr.cytech.hamoudilitim.philo.philosopher.token.Dispatcher.PeerDispatcher;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenMessages.TokenRelease;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenMessages.TokenRequest;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenMessages.TokenTransfer;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenTransport.ChaosTransport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aide de test : N nœuds du protocole à jetons reliés par un réseau en mémoire. Chaque message est livré
 * directement au nœud destinataire. Le réseau peut être perturbé ({@link ChaosTransport}) et un nœud peut
 * « tomber » : plus aucun message ne lui parvient ni n'en part, comme pour un processus arrêté net.
 */
public final class InMemoryNetwork implements TokenTransport, AutoCloseable {

    private final TokenNode[] nodes;
    private final List<PeerDispatcher> dispatchers = new ArrayList<>();
    private final Set<Integer> crashed = ConcurrentHashMap.newKeySet();

    public InMemoryNetwork(int n, double dropRate, Duration maxDelay) {
        nodes = new TokenNode[n];
        TokenTransport transport = dropRate > 0 || !maxDelay.isZero() ? new ChaosTransport(this, dropRate, maxDelay) : this;
        for (int i = 0; i < n; i++) {
            PeerDispatcher dispatcher = new PeerDispatcher(i, n);
            dispatchers.add(dispatcher);
            nodes[i] = new TokenNode(i, n, new LamportClock(), transport, dispatcher, Duration.ofMillis(10));
        }
    }

    public TokenNode node(int i) {
        return nodes[i];
    }

    /** Arrêt brutal de P_i : il garde ses jetons, et plus personne ne peut lui parler. */
    public void crash(int i) {
        crashed.add(i);
    }

    private void deliver(int from, int to) {
        if (crashed.contains(from) || crashed.contains(to)) {
            throw new ChaosTransport.MessageLostException("P" + (crashed.contains(to) ? to : from) + " est en panne");
        }
    }

    @Override
    public void sendRequest(int to, TokenRequest request) {
        deliver(request.requester(), to);
        nodes[to].onRequest(request);
    }

    @Override
    public void sendToken(int to, TokenTransfer token) {
        deliver(token.from(), to);
        nodes[to].onToken(token);
    }

    @Override
    public void sendRelease(int to, TokenRelease release) {
        deliver(release.requester(), to);
        nodes[to].onRelease(release);
    }

    /** Attend que plus aucun jeton ne soit en transit (les réémissions finissent par passer). */
    public void awaitQuiet() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (Arrays.stream(nodes).allMatch(node -> node.state().inTransit().isEmpty())) {
                return;
            }
            Thread.sleep(20);
        }
    }

    public long holdersOf(int fork) {
        return Arrays.stream(nodes).filter(node -> node.holds(fork)).count();
    }

    @Override
    public void close() {
        dispatchers.forEach(PeerDispatcher::close);
    }
}
