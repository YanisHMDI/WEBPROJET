package fr.cytech.philo.philosopher.core;

import fr.cytech.philo.common.PhilosopherEvent;
import fr.cytech.philo.philosopher.core.EventReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Envoie les événements au monitor-service. Les erreurs sont journalisées puis ignorées :
 * le moniteur est un observateur, sa panne ne doit pas affecter les philosophes.
 */
public class HttpEventReporter implements EventReporter {

    private static final Logger log = LoggerFactory.getLogger(HttpEventReporter.class);

    private final RestClient http;

    public HttpEventReporter(RestClient http) {
        this.http = http;
    }

    @Override
    public void report(PhilosopherEvent event) {
        log.info("P{} {} (h={})", event.philosopherId(), event.type(), event.lamport());
        try {
            http.post().uri("/api/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("moniteur injoignable, événement perdu : {}", e.getMessage());
        }
    }
}
