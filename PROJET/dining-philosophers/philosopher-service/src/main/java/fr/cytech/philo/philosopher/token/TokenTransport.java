package fr.cytech.philo.philosopher.token;

/**
 * Envoi des messages du protocole à un autre philosophe. Appels synchrones : une exception signifie
 * que l'on ne sait pas si le message est arrivé (perdu, ou réponse perdue).
 */
public interface TokenTransport {

    void sendRequest(int to, TokenRequest request);

    void sendToken(int to, TokenTransfer token);

    void sendRelease(int to, TokenRelease release);
}
