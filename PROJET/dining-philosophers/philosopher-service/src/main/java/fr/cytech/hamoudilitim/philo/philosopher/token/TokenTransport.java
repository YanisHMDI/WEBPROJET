package fr.cytech.hamoudilitim.philo.philosopher.token;

import fr.cytech.hamoudilitim.philo.philosopher.token.TokenMessages.TokenRelease;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenMessages.TokenRequest;
import fr.cytech.hamoudilitim.philo.philosopher.token.TokenMessages.TokenTransfer;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Envoi des messages du protocole à un autre philosophe. Appels synchrones : une exception signifie
 * que l'on ne sait pas si le message est arrivé (perdu, ou réponse perdue).
 */
public interface TokenTransport {

    void sendRequest(int to, TokenRequest request);

    void sendToken(int to, TokenTransfer token);

    void sendRelease(int to, TokenRelease release);

    /**
     * Messages du protocole envoyés en HTTP (POST JSON) aux autres philosophes. Une réponse 2xx vaut accusé
     * de réception ; toute exception (délai dépassé, refus de connexion, 5xx) est remontée à l'appelant.
     */
    public class HttpTokenTransport implements TokenTransport {

        private final RestClient http;
        private final PeerDirectory peers;

        public HttpTokenTransport(RestClient http, PeerDirectory peers) {
            this.http = http;
            this.peers = peers;
        }

        @Override
        public void sendRequest(int to, TokenRequest request) {
            post(to, "/api/token/request", request);
        }

        @Override
        public void sendToken(int to, TokenTransfer token) {
            post(to, "/api/token/transfer", token);
        }

        @Override
        public void sendRelease(int to, TokenRelease release) {
            post(to, "/api/token/release", release);
        }

        private void post(int to, String path, Object body) {
            http.post().uri(peers.urlOf(to) + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    /**
     * Injection de pannes pour les tests de résilience (désactivée par défaut).
     * <ul>
     *   <li>{@code dropRate} : probabilité de perdre un message AVANT envoi ;</li>
     *   <li>la même probabilité de perdre l'accusé de réception APRÈS envoi : l'émetteur croit à un échec et
     *       réémet, le destinataire reçoit donc un doublon ;</li>
     *   <li>{@code maxDelay} : latence réseau aléatoire dans [0, maxDelay].</li>
     * </ul>
     */
    public class ChaosTransport implements TokenTransport {

        private final TokenTransport delegate;
        private final double dropRate;
        private final Duration maxDelay;

        public ChaosTransport(TokenTransport delegate, double dropRate, Duration maxDelay) {
            if (dropRate < 0 || dropRate >= 1) {
                throw new IllegalArgumentException("dropRate doit être dans [0, 1[");
            }
            this.delegate = delegate;
            this.dropRate = dropRate;
            this.maxDelay = maxDelay == null ? Duration.ZERO : maxDelay;
        }

        @Override
        public void sendRequest(int to, TokenRequest request) {
            around(() -> delegate.sendRequest(to, request));
        }

        @Override
        public void sendToken(int to, TokenTransfer token) {
            around(() -> delegate.sendToken(to, token));
        }

        @Override
        public void sendRelease(int to, TokenRelease release) {
            around(() -> delegate.sendRelease(to, release));
        }

        private void around(Runnable send) {
            delay();
            if (lose()) {
                throw new MessageLostException("message perdu (simulé)");
            }
            send.run();
            if (lose()) {
                throw new MessageLostException("accusé de réception perdu (simulé)");
            }
        }

        private boolean lose() {
            return dropRate > 0 && ThreadLocalRandom.current().nextDouble() < dropRate;
        }

        private void delay() {
            long max = maxDelay.toMillis();
            if (max <= 0) {
                return;
            }
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(max + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MessageLostException("interrompu");
            }
        }

        public static class MessageLostException extends RuntimeException {
            public MessageLostException(String message) {
                super(message);
            }
        }
    }

    /**
     * Adresses HTTP des philosophes, indexées par identifiant. Modifiable à chaud : les tests d'intégration
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
                throw new IllegalStateException("adresse du philosophe " + peer + " inconnue (philo.token.peers)");
            }
            return snapshot.get(peer);
        }
    }
}
