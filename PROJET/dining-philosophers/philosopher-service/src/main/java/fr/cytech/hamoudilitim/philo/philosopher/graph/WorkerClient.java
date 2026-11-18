package fr.cytech.hamoudilitim.philo.philosopher.graph;

import fr.cytech.hamoudilitim.philo.philosopher.PeerDirectory;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.DegreeResult;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.DegreeTask;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.ExpandRequest;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.ExpandResult;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.PartitionLoad;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Partie 5 — comment le Master parle à un Worker. Toute exception = tâche échouée sur ce Worker.
 */
public interface WorkerClient {

    DegreeResult degree(int worker, DegreeTask task);

    void load(int worker, PartitionLoad load);

    ExpandResult expand(int worker, ExpandRequest request);

    void drop(int worker, String jobId);

    /** Envoi en HTTP aux autres philosophes (adresses de {@code philo.peers}). */
    class HttpWorkerClient implements WorkerClient {

        private final RestClient http;
        private final PeerDirectory peers;

        public HttpWorkerClient(RestClient http, PeerDirectory peers) {
            this.http = http;
            this.peers = peers;
        }

        @Override
        public DegreeResult degree(int worker, DegreeTask task) {
            return post(worker, "/api/worker/degree", task, DegreeResult.class);
        }

        @Override
        public void load(int worker, PartitionLoad load) {
            post(worker, "/api/worker/partition", load, Void.class);
        }

        @Override
        public ExpandResult expand(int worker, ExpandRequest request) {
            return post(worker, "/api/worker/expand", request, ExpandResult.class);
        }

        @Override
        public void drop(int worker, String jobId) {
            http.delete().uri(peers.urlOf(worker) + "/api/worker/jobs/{job}", jobId).retrieve().toBodilessEntity();
        }

        private <T> T post(int worker, String path, Object body, Class<T> type) {
            return http.post().uri(peers.urlOf(worker) + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(type);
        }
    }
}
