package fr.cytech.hamoudilitim.philo.philosopher.zookeeper;

import fr.cytech.hamoudilitim.philo.philosopher.core.DiningStrategy;
import fr.cytech.hamoudilitim.philo.philosopher.core.ForkAssignment;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.state.ConnectionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Partie 3.4 — exclusion mutuelle déléguée à ZooKeeper, via la recette « verrou distribué » d'Apache Curator.
 * <p>
 * Chaque fourchette F_i est un verrou {@link InterProcessMutex} sous {@code <root>/forks/F<i>}. Prendre le
 * verrou = créer un nœud <b>éphémère et séquentiel</b> sous ce chemin ; le plus petit numéro détient le
 * verrou, les autres surveillent leur prédécesseur (pas de « troupeau » réveillé d'un coup).
 * <ul>
 *   <li><b>exclusion mutuelle</b> : garantie par ZooKeeper (un seul plus petit numéro) ;</li>
 *   <li><b>pas d'interblocage</b> : les deux verrous sont toujours pris dans le même ordre global
 *       (plus petit indice d'abord), ce qui interdit l'attente circulaire ;</li>
 *   <li><b>équité, pas de famine</b> : chaque verrou sert ses demandeurs dans l'ordre d'arrivée (FIFO) ;</li>
 *   <li><b>panne d'un philosophe</b> : ses nœuds sont éphémères, ZooKeeper les supprime à l'expiration de sa
 *       session, donc ses fourchettes sont libérées automatiquement (ce que le protocole à jetons ne sait
 *       pas faire).</li>
 * </ul>
 * Le prix : un service central (ZooKeeper, répliqué en production) et un aller-retour vers lui par opération.
 */
public class ZooKeeperStrategy implements DiningStrategy {

    private static final Logger log = LoggerFactory.getLogger(ZooKeeperStrategy.class);

    private final int id;
    private final int count;
    private final CuratorFramework client;
    private final ForkAssignment assignment;
    private final Map<Integer, InterProcessMutex> locks = new HashMap<>();
    private final List<InterProcessMutex> held = new ArrayList<>();
    private volatile List<Integer> eatingWith = List.of();

    public ZooKeeperStrategy(int id, int count, CuratorFramework client, ForkAssignment assignment, String root) {
        this.id = id;
        this.count = count;
        this.client = client;
        this.assignment = assignment;
        for (int fork = 0; fork < count; fork++) {
            locks.put(fork, new InterProcessMutex(client, root + "/forks/F" + fork));
        }
        client.getConnectionStateListenable().addListener((c, state) -> {
            if (state == ConnectionState.LOST) {
                // la session a expiré : ZooKeeper a peut-être déjà donné nos fourchettes à quelqu'un d'autre
                log.error("P{} : session ZooKeeper perdue, les verrous détenus ne sont plus garantis", id);
            }
        });
    }

    @Override
    public void acquireForks() throws InterruptedException {
        List<Integer> forks = new ArrayList<>(assignment.forksFor(id, count));
        forks.sort(null);                               // ordre global : plus petit indice d'abord
        try {
            for (int fork : forks) {
                InterProcessMutex lock = locks.get(fork);
                lock.acquire();                         // bloque jusqu'à notre tour (FIFO)
                held.add(lock);
            }
            eatingWith = List.copyOf(forks);
        } catch (InterruptedException e) {
            releaseForks();
            throw e;
        } catch (Exception e) {
            releaseForks();
            throw new IllegalStateException("P" + id + " : ZooKeeper injoignable (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public void releaseForks() {
        eatingWith = List.of();
        // Philosophe arrêté pendant qu'il mange : son thread est interrompu, et Curator refuse alors de parler à
        // ZooKeeper. On efface le drapeau le temps de rendre les verrous, sinon ils resteraient pris jusqu'à
        // l'expiration de la session.
        boolean interrupted = Thread.interrupted();
        try {
            for (int i = held.size() - 1; i >= 0; i--) {
                try {
                    held.get(i).release();
                } catch (Exception e) {
                    // session perdue : le nœud éphémère a déjà disparu côté ZooKeeper
                    log.warn("P{} : libération impossible ({})", id, e.getMessage());
                }
            }
            held.clear();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public List<Integer> currentForks() {
        return eatingWith;
    }

    public CuratorFramework client() {
        return client;
    }
}
