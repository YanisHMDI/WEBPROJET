package fr.cytech.hamoudilitim.philo.monitor;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * Panneau de contrôle du tableau de bord : le navigateur ne parle qu'au moniteur, qui relaie vers les
 * philosophes (pas de problème d'origine croisée, une seule adresse à ouvrir).
 * <ul>
 *   <li>{@code GET /api/cluster} : état de chaque philosophe (joignable ou non, mode, repas) ;</li>
 *   <li>{@code POST /api/cluster/{id}/start|stop} : démarrer / arrêter un philosophe (simuler une panne) ;</li>
 *   <li>{@code GET /api/cluster/{id}/token-state} : jetons et file d'attente (mode TOKEN) ;</li>
 *   <li>{@code POST /api/cluster/master/{degrees|reachability}} : lancer un calcul Master–Worker (partie 5).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/cluster")
public class ClusterController {

    private final List<String> urls;
    private final RestClient fast;
    private final RestClient slow;

    public ClusterController(MonitorProperties props) {
        this.urls = props.philosopherUrls();
        this.fast = client(800);
        this.slow = client(120_000);
    }

    private static RestClient client(int readTimeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(800);
        factory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().requestFactory(factory).build();
    }

    @GetMapping
    public List<Map<String, Object>> cluster() {
        List<CompletableFuture<Map<String, Object>>> calls = IntStream.range(0, urls.size())
                .mapToObj(id -> CompletableFuture.supplyAsync(() -> status(id))).toList();
        return calls.stream().map(CompletableFuture::join).toList();
    }

    private Map<String, Object> status(int id) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("url", urls.get(id));
        try {
            Map<?, ?> status = fast.get().uri(urls.get(id) + "/api/status").retrieve().body(Map.class);
            node.put("reachable", true);
            node.put("status", status);
        } catch (RuntimeException e) {
            node.put("reachable", false);
        }
        return node;
    }

    @PostMapping("/{id}/{action:start|stop}")
    public ResponseEntity<String> control(@PathVariable int id, @PathVariable String action) {
        return relay(() -> fast.post().uri(url(id) + "/api/" + action).retrieve().toEntity(String.class));
    }

    @GetMapping("/{id}/token-state")
    public ResponseEntity<String> tokenState(@PathVariable int id) {
        return relay(() -> fast.get().uri(url(id) + "/api/token/state").retrieve().toEntity(String.class));
    }

    @PostMapping("/master/{kind:degrees|reachability}")
    public ResponseEntity<String> master(@PathVariable String kind, @RequestParam(defaultValue = "0") int master,
                                         @RequestBody Map<String, Object> job) {
        return relay(() -> slow.post().uri(url(master) + "/api/master/" + kind)
                .contentType(MediaType.APPLICATION_JSON).body(job).retrieve().toEntity(String.class));
    }

    private String url(int id) {
        if (id < 0 || id >= urls.size()) {
            throw new IllegalArgumentException("philosophe " + id + " inconnu");
        }
        return urls.get(id);
    }

    private interface Call {
        ResponseEntity<String> run();
    }

    /** Recopie la réponse du philosophe ; 502 s'il est injoignable. */
    private static ResponseEntity<String> relay(Call call) {
        try {
            ResponseEntity<String> r = call.run();
            return ResponseEntity.status(r.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                    .body(r.getBody() == null ? "{}" : r.getBody());
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(HttpStatusCode.valueOf(e.getStatusCode().value()))
                    .contentType(MediaType.APPLICATION_JSON).body(e.getResponseBodyAsString());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body("{\"detail\":\"" + e.getMessage() + "\"}");
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"detail\":\"philosophe injoignable\"}");
        }
    }
}
