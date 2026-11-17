package fr.cytech.hamoudilitim.philo.philosopher.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Vérifie le contrat HTTP avec le superviseur et le comportement en cas de panne.
 */
class SupervisorClientTest {

    private MockRestServiceServer server;
    private SupervisorClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://supervisor");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SupervisorClient(builder.build());
    }

    @Test
    void acquirePairParsesGrant() {
        server.expect(requestTo("http://supervisor/api/pairs/2/acquire"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"granted\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.acquirePair(2)).isTrue();
        server.verify();
    }

    @Test
    void availabilityIsReadFromBooleanBody() {
        server.expect(requestTo("http://supervisor/api/forks/3/available"))
                .andRespond(withSuccess("false", MediaType.APPLICATION_JSON));

        assertThat(client.isAvailable(3)).isFalse();
    }

    @Test
    void serverErrorIsTreatedAsRefusal() {
        server.expect(requestTo("http://supervisor/api/forks/1/acquire?philosopher=0"))
                .andRespond(withServerError());

        assertThat(client.acquireFork(1, 0)).isFalse();
    }

    @Test
    void releaseFailureDoesNotThrow() {
        server.expect(requestTo("http://supervisor/api/pairs/0/release"))
                .andRespond(withServerError());

        client.releasePair(0); // ne doit pas lever d'exception
        server.verify();
    }
}
