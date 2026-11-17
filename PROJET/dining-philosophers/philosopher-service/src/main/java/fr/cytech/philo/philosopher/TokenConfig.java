package fr.cytech.philo.philosopher;

import fr.cytech.philo.common.LamportClock;
import fr.cytech.philo.philosopher.token.ChaosTransport;
import fr.cytech.philo.philosopher.token.HttpTokenTransport;
import fr.cytech.philo.philosopher.token.PeerDirectory;
import fr.cytech.philo.philosopher.token.PeerDispatcher;
import fr.cytech.philo.philosopher.token.TokenNode;
import fr.cytech.philo.philosopher.token.TokenTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans du protocole à jetons, créés uniquement en mode TOKEN.
 */
@Configuration
@ConditionalOnProperty(name = "philo.mode", havingValue = "TOKEN")
public class TokenConfig {

    @Bean
    PeerDirectory peerDirectory(PhilosopherProperties props) {
        var peers = props.token().peers();
        if (!peers.isEmpty() && peers.size() != props.count()) {
            throw new IllegalArgumentException("philo.token.peers doit lister " + props.count()
                    + " adresses (une par philosophe), reçu " + peers.size());
        }
        return new PeerDirectory(peers);
    }

    @Bean(destroyMethod = "close")
    PeerDispatcher tokenDispatcher(PhilosopherProperties props) {
        return new PeerDispatcher(props.id(), props.count());
    }

    @Bean
    TokenTransport tokenTransport(PhilosopherProperties props, PeerDirectory peers) {
        TokenTransport http = new HttpTokenTransport(PhilosopherConfig.restClient(null, props), peers);
        var t = props.token();
        if (t.dropRate() > 0 || !t.maxDelay().isZero()) {
            return new ChaosTransport(http, t.dropRate(), t.maxDelay());
        }
        return http;
    }

    @Bean
    TokenNode tokenNode(PhilosopherProperties props, LamportClock clock, TokenTransport transport,
                        PeerDispatcher dispatcher) {
        return new TokenNode(props.id(), props.count(), clock, transport, dispatcher, props.token().retryDelay());
    }
}
