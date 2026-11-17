package fr.cytech.philo.philosopher.web;

import fr.cytech.philo.philosopher.token.TokenNode;
import fr.cytech.philo.philosopher.token.TokenRelease;
import fr.cytech.philo.philosopher.token.TokenRequest;
import fr.cytech.philo.philosopher.token.TokenTransfer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partie 3.3 — points d'entrée pair-à-pair du protocole à jetons (actifs uniquement en mode TOKEN).
 * Ils ne font que transmettre le message au {@link TokenNode} : la réponse 2xx sert d'accusé de réception.
 */
@RestController
@RequestMapping("/api/token")
@ConditionalOnProperty(name = "philo.mode", havingValue = "TOKEN")
public class TokenController {

    private final TokenNode node;

    public TokenController(TokenNode node) {
        this.node = node;
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void request(@RequestBody TokenRequest request) {
        node.onRequest(request);
    }

    @PostMapping("/transfer")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void transfer(@RequestBody TokenTransfer token) {
        node.onToken(token);
    }

    @PostMapping("/release")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void release(@RequestBody TokenRelease release) {
        node.onRelease(release);
    }

    @GetMapping("/state")
    public TokenNode.State state() {
        return node.state();
    }
}
