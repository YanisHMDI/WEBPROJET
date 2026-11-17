package fr.cytech.philo.philosopher.token;

import java.time.Duration;

/**
 * Exécute les envois réseau hors du verrou du {@link TokenNode}.
 * <p>
 * Le nœud ne fait jamais d'appel HTTP en tenant son verrou : sinon deux philosophes qui s'envoient un
 * message en même temps attendraient chacun la réponse de l'autre (interblocage distribué).
 */
public interface Dispatcher {

    void submit(int peer, Runnable task);

    void submitLater(int peer, Runnable task, Duration delay);
}
