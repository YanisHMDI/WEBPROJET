package fr.cytech.philo.philosopher.token;

import fr.cytech.philo.philosopher.core.DiningStrategy;
import fr.cytech.philo.philosopher.token.TokenMessages.TokenRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

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

    /**
     * Règle d'attribution automatique des deux fourchettes d'un philosophe pour un repas.
     */
    @FunctionalInterface
    public interface ForkAssignment {

        List<Integer> forksFor(int philosopher, int count);

        enum Rule { NEIGHBOURS, RANDOM }

        static ForkAssignment of(Rule rule) {
            return switch (rule) {
                case NEIGHBOURS -> neighbours();
                case RANDOM -> random(new Random());
            };
        }

        /** Table ronde classique : F_i et F_{(i+1) mod N}. */
        static ForkAssignment neighbours() {
            return (p, n) -> List.of(p, (p + 1) % n);
        }

        /**
         * Règle par défaut (« fourchettes quelconques, sans contrainte de voisinage ») : la fourchette F_i
         * que le philosophe gère, plus une autre tirée uniformément parmi les N-1 restantes, à chaque repas.
         * Garder F_i assure que chaque fourchette est régulièrement réclamée par son « propriétaire ».
         */
        static ForkAssignment random(RandomGenerator rnd) {
            return (p, n) -> {
                int other = rnd.nextInt(n - 1);
                if (other >= p) {
                    other++;
                }
                return List.of(p, other);
            };
        }
    }
}
