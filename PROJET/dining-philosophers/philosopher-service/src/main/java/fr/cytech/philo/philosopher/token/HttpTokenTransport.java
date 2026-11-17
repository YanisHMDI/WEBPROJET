package fr.cytech.philo.philosopher.token;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

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
