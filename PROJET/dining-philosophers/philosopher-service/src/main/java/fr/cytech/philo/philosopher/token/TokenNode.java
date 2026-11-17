package fr.cytech.philo.philosopher.token;

import fr.cytech.philo.common.LamportClock;
import fr.cytech.philo.philosopher.token.TokenMessages.TokenRelease;
import fr.cytech.philo.philosopher.token.TokenMessages.TokenRequest;
import fr.cytech.philo.philosopher.token.TokenMessages.TokenTransfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Partie 3.3 — état local d'un philosophe dans le protocole distribué à jetons.
 * <p>
 * <b>Principe.</b> Chaque fourchette F_f possède un jeton unique ; au départ, P_i détient le jeton de F_i.
 * Un philosophe affamé diffuse une {@link TokenRequest} horodatée (Lamport) à tous les autres. Chacun
 * mémorise toutes les demandes. Le détenteur d'un jeton le donne à la demande <b>la plus prioritaire</b>
 * (plus petit (lamport, id)) qui en a besoin, sauf s'il mange avec, ou si sa propre demande est plus
 * prioritaire. Un philosophe ne mange que lorsqu'il détient les deux jetons de sa demande.
 * <p>
 * <b>Invariants</b> (justifiés dans docs/protocole-jetons.md) :
 * <ul>
 *   <li>sûreté : un jeton n'est jamais à deux endroits (il quitte {@code held} avant d'être envoyé, et un
 *       jeton reçu dont le numéro de transfert n'est pas nouveau est ignoré) ;</li>
 *   <li>pas d'interblocage : un affamé qui n'a pas encore mangé cède ses jetons à une demande plus
 *       prioritaire ; la demande la plus prioritaire du système n'attend donc que des repas en cours ;</li>
 *   <li>pas de famine : une demande créée après réception de la nôtre a un horodatage plus grand, donc
 *       une priorité plus faible ; seul un nombre fini de demandes peut nous passer devant.</li>
 * </ul>
 * <b>Concurrence.</b> Les messages arrivent sur les threads HTTP pendant que la boucle du philosophe
 * tourne : tout l'état est protégé par le moniteur de l'objet ({@code synchronized}). Les envois réseau
 * sont préparés sous verrou mais exécutés après, par le {@link Dispatcher}.
 */
public class TokenNode {

    private static final Logger log = LoggerFactory.getLogger(TokenNode.class);
    private static final int RELEASE_ATTEMPTS = 3;

    private final int id;
    private final int count;
    private final LamportClock clock;
    private final TokenTransport transport;
    private final Dispatcher dispatcher;
    private final Duration retryDelay;

    /** Jetons présents ici : fourchette → numéro de transfert. */
    private final Map<Integer, Long> held = new TreeMap<>();
    /** Plus grand numéro de transfert vu pour chaque fourchette (détection des doublons). */
    private final Map<Integer, Long> lastSeenSeq = new HashMap<>();
    /** Jetons envoyés dont on attend l'accusé de réception (réémis tant qu'il n'est pas reçu). */
    private final Map<Integer, TokenTransfer> inTransit = new TreeMap<>();
    /** Dernière demande connue de chaque philosophe (y compris la nôtre). */
    private final Map<Integer, TokenRequest> pending = new HashMap<>();
    /** released[p] = dernier repas de p que l'on sait terminé. */
    private final long[] released;

    private long mealCounter;
    private TokenRequest current;
    private boolean eating;

    private final AtomicLong requestsSent = new AtomicLong();
    private final AtomicLong tokensSent = new AtomicLong();
    private final AtomicLong releasesSent = new AtomicLong();
    private final AtomicLong retransmissions = new AtomicLong();
    private final AtomicLong duplicatesIgnored = new AtomicLong();
    private final AtomicLong staleIgnored = new AtomicLong();

    private record Outgoing(int peer, Runnable task) {
    }

    public TokenNode(int id, int count, LamportClock clock, TokenTransport transport,
                     Dispatcher dispatcher, Duration retryDelay) {
        if (count < 2 || id < 0 || id >= count) {
            throw new IllegalArgumentException("philosophe " + id + " invalide pour N=" + count);
        }
        this.id = id;
        this.count = count;
        this.clock = clock;
        this.transport = transport;
        this.dispatcher = dispatcher;
        this.retryDelay = retryDelay;
        this.released = new long[count];
        held.put(id, 0L);
        lastSeenSeq.put(id, 0L);
    }

    // ------------------------------------------------------------------ côté boucle du philosophe

    /** Crée et diffuse une demande pour ces deux fourchettes. */
    public TokenRequest requestForks(List<Integer> forks) {
        List<Outgoing> out = new ArrayList<>();
        TokenRequest request;
        synchronized (this) {
            if (current != null) {
                throw new IllegalStateException("P" + id + " a déjà une demande en cours : " + current.key());
            }
            forks.forEach(this::checkFork);
            request = new TokenRequest(id, ++mealCounter, clock.tick(), forks);
            current = request;
            pending.put(id, request);
            broadcastRequest(request, out);
            dispatch(out);
        }
        flush(out);
        return request;
    }

    /** Réémet la demande en cours (même horodatage, donc même priorité) : utile si un message s'est perdu. */
    public void retransmit() {
        List<Outgoing> out = new ArrayList<>();
        synchronized (this) {
            if (current == null || eating) {
                return;
            }
            retransmissions.incrementAndGet();
            broadcastRequest(current, out);
        }
        flush(out);
    }

    /**
     * Attend d'avoir les deux jetons, au plus {@code maxWait}.
     *
     * @return true si le philosophe peut manger (il passe alors en état « mange »)
     */
    public synchronized boolean awaitForks(Duration maxWait) throws InterruptedException {
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (!tryStartEating()) {
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMs <= 0) {
                return false;
            }
            wait(remainingMs);
        }
        return true;
    }

    /** Fin du repas, ou abandon de la demande : on l'annonce et on cède les jetons attendus ailleurs. */
    public void releaseRequest() {
        List<Outgoing> out = new ArrayList<>();
        synchronized (this) {
            if (current == null) {
                return;
            }
            TokenRelease release = new TokenRelease(id, current.meal(), clock.tick());
            released[id] = Math.max(released[id], current.meal());
            pending.remove(id);
            current = null;
            eating = false;
            for (int peer = 0; peer < count; peer++) {
                if (peer != id) {
                    out.add(new Outgoing(peer, releaseTask(peer, release, RELEASE_ATTEMPTS)));
                }
            }
            dispatch(out);
        }
        flush(out);
    }

    public synchronized List<Integer> currentForks() {
        return eating ? current.forks() : List.of();
    }

    // ------------------------------------------------------------------ côté messages reçus (threads HTTP)

    public void onRequest(TokenRequest request) {
        List<Outgoing> out = new ArrayList<>();
        synchronized (this) {
            clock.onReceive(request.lamport());
            int from = request.requester();
            checkPeer(from);
            request.forks().forEach(this::checkFork);
            if (from == id) {
                return;
            }
            if (request.meal() <= released[from]) {
                staleIgnored.incrementAndGet();      // demande déjà terminée (retransmission tardive)
                return;
            }
            // un philosophe n'a qu'une demande à la fois : demander le repas k, c'est avoir fini le k-1
            released[from] = Math.max(released[from], request.meal() - 1);
            TokenRequest known = pending.get(from);
            if (known != null && known.meal() >= request.meal()) {
                duplicatesIgnored.incrementAndGet();
                return;
            }
            pending.put(from, request);
            dispatch(out);
        }
        flush(out);
    }

    public void onToken(TokenTransfer token) {
        List<Outgoing> out = new ArrayList<>();
        synchronized (this) {
            clock.onReceive(token.lamport());
            checkFork(token.fork());
            if (token.to() != id) {
                throw new IllegalArgumentException("jeton F" + token.fork() + " adressé à P" + token.to());
            }
            mergeReleased(token.released());
            long seen = lastSeenSeq.getOrDefault(token.fork(), -1L);
            if (token.seq() <= seen) {
                duplicatesIgnored.incrementAndGet(); // réémission : on l'a déjà eu, on accuse quand même réception
                return;
            }
            lastSeenSeq.put(token.fork(), token.seq());
            held.put(token.fork(), token.seq());
            log.debug("P{} reçoit le jeton F{} de P{} (transfert n°{})", id, token.fork(), token.from(), token.seq());
            dispatch(out);
            notifyAll();
        }
        flush(out);
    }

    public void onRelease(TokenRelease release) {
        List<Outgoing> out = new ArrayList<>();
        synchronized (this) {
            clock.onReceive(release.lamport());
            checkPeer(release.requester());
            released[release.requester()] = Math.max(released[release.requester()], release.meal());
            prunePending();
            dispatch(out);
        }
        flush(out);
    }

    // ------------------------------------------------------------------ règle de transfert

    /**
     * Pour chaque jeton présent : le donner à la demande la plus prioritaire qui en a besoin.
     * On ne garde un jeton que si l'on mange avec, ou si notre propre demande est la plus prioritaire.
     */
    private void dispatch(List<Outgoing> out) {
        for (Integer fork : new ArrayList<>(held.keySet())) {
            if (eating && current.needs(fork)) {
                continue;
            }
            TokenRequest best = null;
            for (TokenRequest r : pending.values()) {
                if (r.needs(fork) && (best == null || TokenRequest.PRIORITY.compare(r, best) < 0)) {
                    best = r;
                }
            }
            if (best == null || best.requester() == id) {
                continue;
            }
            long seq = held.remove(fork) + 1;
            lastSeenSeq.put(fork, seq);
            TokenTransfer transfer = new TokenTransfer(fork, seq, id, best.requester(), clock.tick(), releasedVector());
            inTransit.put(fork, transfer);
            log.debug("P{} cède F{} à P{} pour {}", id, fork, best.requester(), best.key());
            out.add(new Outgoing(best.requester(), tokenTask(transfer)));
        }
    }

    private boolean tryStartEating() {
        if (current != null && !eating && held.keySet().containsAll(current.forks())) {
            eating = true;
        }
        return eating;
    }

    private void mergeReleased(List<Long> vector) {
        for (int p = 0; p < Math.min(vector.size(), count); p++) {
            if (p != id) {
                released[p] = Math.max(released[p], vector.get(p));
            }
        }
        prunePending();
    }

    private void prunePending() {
        pending.values().removeIf(r -> r.requester() != id && r.meal() <= released[r.requester()]);
    }

    private List<Long> releasedVector() {
        return Arrays.stream(released).boxed().toList();
    }

    // ------------------------------------------------------------------ envois (hors verrou)

    private void broadcastRequest(TokenRequest request, List<Outgoing> out) {
        for (int peer = 0; peer < count; peer++) {
            if (peer == id) {
                continue;
            }
            int to = peer;
            out.add(new Outgoing(to, () -> {
                try {
                    requestsSent.incrementAndGet();
                    transport.sendRequest(to, request);
                } catch (RuntimeException e) {
                    // pas de réémission ici : la boucle du philosophe réémet périodiquement sa demande
                    log.debug("P{} : demande {} vers P{} perdue ({})", id, request.key(), to, e.getMessage());
                }
            }));
        }
    }

    /** Réémis jusqu'à accusé de réception : un jeton ne doit jamais disparaître. */
    private Runnable tokenTask(TokenTransfer transfer) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    tokensSent.incrementAndGet();
                    transport.sendToken(transfer.to(), transfer);
                    synchronized (TokenNode.this) {
                        inTransit.remove(transfer.fork(), transfer);
                    }
                } catch (RuntimeException e) {
                    log.warn("P{} : jeton F{} vers P{} non acquitté ({}), nouvel essai dans {}",
                            id, transfer.fork(), transfer.to(), e.getMessage(), retryDelay);
                    dispatcher.submitLater(transfer.to(), this, retryDelay);
                }
            }
        };
    }

    /** Best effort : une libération perdue est rattrapée par le vecteur {@code released} des jetons. */
    private Runnable releaseTask(int to, TokenRelease release, int attemptsLeft) {
        return () -> {
            try {
                releasesSent.incrementAndGet();
                transport.sendRelease(to, release);
            } catch (RuntimeException e) {
                if (attemptsLeft > 1) {
                    dispatcher.submitLater(to, releaseTask(to, release, attemptsLeft - 1), retryDelay);
                }
            }
        };
    }

    private void flush(List<Outgoing> out) {
        for (Outgoing o : out) {
            dispatcher.submit(o.peer(), o.task());
        }
    }

    private void checkFork(int fork) {
        if (fork < 0 || fork >= count) {
            throw new IllegalArgumentException("fourchette " + fork + " hors de [0, " + (count - 1) + "]");
        }
    }

    private void checkPeer(int peer) {
        if (peer < 0 || peer >= count) {
            throw new IllegalArgumentException("philosophe " + peer + " hors de [0, " + (count - 1) + "]");
        }
    }

    // ------------------------------------------------------------------ observation

    public record State(int id, long lamport, TokenRequest current, boolean eating,
                        List<Integer> heldTokens, Map<Integer, Integer> inTransit, List<TokenRequest> queue,
                        List<Long> released, Stats stats) {
    }

    public record Stats(long requestsSent, long tokensSent, long releasesSent, long retransmissions,
                        long duplicatesIgnored, long staleIgnored) {
    }

    public synchronized State state() {
        Map<Integer, Integer> transit = new TreeMap<>();
        inTransit.forEach((fork, t) -> transit.put(fork, t.to()));
        List<TokenRequest> queue = pending.values().stream().sorted(TokenRequest.PRIORITY).toList();
        return new State(id, clock.current(), current, eating, List.copyOf(held.keySet()), transit, queue,
                releasedVector(), stats());
    }

    public Stats stats() {
        return new Stats(requestsSent.get(), tokensSent.get(), releasesSent.get(), retransmissions.get(),
                duplicatesIgnored.get(), staleIgnored.get());
    }

    public synchronized boolean holds(int fork) {
        return held.containsKey(fork);
    }

    public int id() {
        return id;
    }
}
