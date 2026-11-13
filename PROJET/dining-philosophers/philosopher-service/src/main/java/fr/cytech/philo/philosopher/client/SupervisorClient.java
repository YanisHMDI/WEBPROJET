package fr.cytech.philo.philosopher.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.function.Supplier;

/**
 * Client HTTP du supervisor-service.
 * <p>
 * En cas d'erreur réseau, une demande est considérée comme refusée (false) : le philosophe
 * réessaiera. ATTENTION : si la réponse est perdue alors que le superviseur a bien attribué
 * la fourchette, elle restera bloquée. C'est une limite à analyser en partie 4 (résilience).
 */
public class SupervisorClient {

    private static final Logger log = LoggerFactory.getLogger(SupervisorClient.class);

    public record Grant(boolean granted) {
    }

    private final RestClient http;

    public SupervisorClient(RestClient http) {
        this.http = http;
    }

    public boolean isAvailable(int fork) {
        try {
            return Boolean.TRUE.equals(http.get().uri("/api/forks/{f}/available", fork)
                    .retrieve().body(Boolean.class));
        } catch (RestClientException e) {
            log.warn("superviseur injoignable (available F{}) : {}", fork, e.getMessage());
            return false;
        }
    }

    public boolean acquireFork(int fork, int philosopher) {
        return grant(() -> http.post().uri("/api/forks/{f}/acquire?philosopher={p}", fork, philosopher)
                .retrieve().body(Grant.class));
    }

    public void releaseFork(int fork, int philosopher) {
        call(() -> http.post().uri("/api/forks/{f}/release?philosopher={p}", fork, philosopher)
                .retrieve().toBodilessEntity());
    }

    public boolean acquirePair(int philosopher) {
        return grant(() -> http.post().uri("/api/pairs/{p}/acquire", philosopher)
                .retrieve().body(Grant.class));
    }

    public void releasePair(int philosopher) {
        call(() -> http.post().uri("/api/pairs/{p}/release", philosopher)
                .retrieve().toBodilessEntity());
    }

    private boolean grant(Supplier<Grant> request) {
        try {
            Grant g = request.get();
            return g != null && g.granted();
        } catch (RestClientException e) {
            log.warn("superviseur injoignable : {}", e.getMessage());
            return false;
        }
    }

    private void call(Runnable request) {
        try {
            request.run();
        } catch (RestClientException e) {
            log.warn("superviseur injoignable (libération) : {}", e.getMessage());
        }
    }
}
