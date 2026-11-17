package fr.cytech.philo.philosopher.token;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

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
