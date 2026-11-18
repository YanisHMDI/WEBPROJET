package fr.cytech.hamoudilitim.philo.philosopher.token;

import fr.cytech.hamoudilitim.philo.philosopher.core.DiningStrategy;
import fr.cytech.hamoudilitim.philo.philosopher.core.ForkAssignment;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenMessages.TokenRequest;
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
    private final Duration giveUpAfter;

    public TokenStrategy(TokenNode node, ForkAssignment assignment, int count, Duration retransmitInterval) {
        this(node, assignment, count, retransmitInterval, Duration.ZERO);
    }

    /**
     * @param giveUpAfter au-delà de cette attente, la demande est abandonnée puis refaite (0 = jamais).
     *                    Partie 4 : si le détenteur d'un jeton est tombé en panne, l'affamé arrête de bloquer
     *                    les jetons qu'il tient déjà, et une nouvelle demande peut porter sur d'autres fourchettes.
     */
    public TokenStrategy(TokenNode node, ForkAssignment assignment, int count, Duration retransmitInterval,
                         Duration giveUpAfter) {
        this.node = node;
        this.assignment = assignment;
        this.count = count;
        this.retransmitInterval = retransmitInterval;
        this.giveUpAfter = giveUpAfter == null ? Duration.ZERO : giveUpAfter;
    }

    @Override
    public void acquireForks() throws InterruptedException {
        while (true) {
            TokenRequest request = node.requestForks(assignment.forksFor(node.id(), count));
            long start = System.nanoTime();
            int rounds = 0;
            try {
                while (true) {
                    if (node.awaitForks(retransmitInterval)) {
                        return;                  // les deux jetons sont là : on mange
                    }
                    if (!giveUpAfter.isZero() && System.nanoTime() - start > giveUpAfter.toNanos()) {
                        break;
                    }
                    node.retransmit();
                    if (++rounds % 10 == 0) {
                        log.warn("P{} attend {} pour {} depuis {} réémissions", node.id(), request.forks(),
                                request.key(), rounds);
                    }
                }
            } catch (InterruptedException e) {
                node.releaseRequest();           // abandon : on ne garde pas de jetons dont on n'a plus besoin
                throw e;
            }
            log.warn("P{} abandonne {} après {} sans réponse : un détenteur de jeton est peut-être en panne",
                    node.id(), request.key(), giveUpAfter);
            node.releaseRequest();
            Thread.sleep(retransmitInterval.toMillis());
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
