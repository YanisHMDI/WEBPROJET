package fr.cytech.philo.philosopher.token;

import fr.cytech.philo.philosopher.core.DiningStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Partie 3.3 — le philosophe obtient ses fourchettes par le protocole à jetons, sans superviseur.
 * <ol>
 *   <li>la règle d'attribution choisit les deux fourchettes du repas ;</li>
 *   <li>la demande horodatée est diffusée à tous ;</li>
 *   <li>on attend les deux jetons, en réémettant la demande toutes les {@code retransmitInterval}
 *       (même horodatage : la réémission ne fait pas perdre sa place dans la file).</li>
 * </ol>
 */
public class TokenStrategy implements DiningStrategy {

    private static final Logger log = LoggerFactory.getLogger(TokenStrategy.class);

    private final TokenNode node;
    private final ForkAssignment assignment;
    private final int count;
    private final Duration retransmitInterval;

    public TokenStrategy(TokenNode node, ForkAssignment assignment, int count, Duration retransmitInterval) {
        this.node = node;
        this.assignment = assignment;
        this.count = count;
        this.retransmitInterval = retransmitInterval;
    }

    @Override
    public void acquireForks() throws InterruptedException {
        TokenRequest request = node.requestForks(assignment.forksFor(node.id(), count));
        int rounds = 0;
        try {
            while (!node.awaitForks(retransmitInterval)) {
                node.retransmit();
                if (++rounds % 10 == 0) {
                    log.warn("P{} attend {} pour {} depuis {} réémissions", node.id(), request.forks(),
                            request.key(), rounds);
                }
            }
        } catch (InterruptedException e) {
            node.releaseRequest();   // abandon : on ne garde pas de jetons dont on n'a plus besoin
            throw e;
        }
    }

    @Override
    public void releaseForks() {
        node.releaseRequest();
    }

    @Override
    public List<Integer> currentForks() {
        return node.currentForks();
    }
}
