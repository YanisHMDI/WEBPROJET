package fr.cytech.hamoudilitim.philo.philosopher;

import java.util.List;

/**
 * Adresses HTTP des philosophes, indexées par identifiant (protocole à jetons, Master–Worker). Modifiable à chaud : les tests d'intégration
 * démarrent les services sur des ports aléatoires puis renseignent les adresses.
 */
public class PeerDirectory {

    private volatile List<String> urls;

    public PeerDirectory(List<String> urls) {
        this.urls = List.copyOf(urls);
    }

    public void update(List<String> newUrls) {
        this.urls = List.copyOf(newUrls);
    }

    public String urlOf(int peer) {
        List<String> snapshot = urls;
        if (peer < 0 || peer >= snapshot.size()) {
            throw new IllegalStateException("adresse du philosophe " + peer + " inconnue (philo.peers)");
        }
        return snapshot.get(peer);
    }
}
