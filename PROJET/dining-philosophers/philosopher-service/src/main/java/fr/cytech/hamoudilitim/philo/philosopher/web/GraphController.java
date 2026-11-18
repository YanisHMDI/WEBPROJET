package fr.cytech.hamoudilitim.philo.philosopher.web;

import fr.cytech.hamoudilitim.philo.philosopher.PhilosopherProperties;
import fr.cytech.hamoudilitim.philo.philosopher.graph.Graph;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMaster;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMaster.DegreeReport;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMaster.ReachReport;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.DegreeResult;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.DegreeTask;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.ExpandRequest;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.ExpandResult;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphMessages.PartitionLoad;
import fr.cytech.hamoudilitim.philo.philosopher.graph.GraphWorker;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Partie 5 — API Master–Worker. Tout philosophe est Worker ; celui qui reçoit une requête {@code /api/master}
 * devient Master pour ce calcul et utilise les autres philosophes comme Workers.
 */
@RestController
@RequestMapping("/api")
public class GraphController {

    /**
     * Calcul demandé au Master sur un graphe aléatoire reproductible.
     *
     * @param workers nombre de Workers à utiliser (défaut : tous les autres philosophes)
     */
    public record GraphJob(int nodes, int edges, long seed, Integer workers, Integer source, Integer target) {
    }

    private final GraphWorker worker;
    private final GraphMaster master;
    private final PhilosopherProperties props;

    public GraphController(GraphWorker worker, GraphMaster master, PhilosopherProperties props) {
        this.worker = worker;
        this.master = master;
        this.props = props;
    }

    // ------------------------------------------------------------------ rôle Worker

    @PostMapping("/worker/degree")
    public DegreeResult degree(@RequestBody DegreeTask task) {
        return worker.degree(task);
    }

    @PostMapping("/worker/partition")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void load(@RequestBody PartitionLoad load) {
        worker.load(load);
    }

    @PostMapping("/worker/expand")
    public ExpandResult expand(@RequestBody ExpandRequest request) {
        return worker.expand(request);
    }

    @DeleteMapping("/worker/jobs/{jobId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void drop(@PathVariable String jobId) {
        worker.drop(jobId);
    }

    // ------------------------------------------------------------------ rôle Master

    @PostMapping("/master/degrees")
    public DegreeReport degrees(@RequestBody GraphJob job) {
        DegreeReport r = master.degrees(Graph.random(job.nodes(), job.edges(), job.seed()), workers(job));
        // pour un grand graphe, on ne renvoie pas les degrés de tous les nœuds
        return r.nodes() <= 100 ? r : new DegreeReport(r.jobId(), r.nodes(), r.edges(), r.workers(), r.distributedMs(),
                r.sequentialMs(), r.correct(), r.sumOfDegrees(), r.maxDegree(), r.tasks(), r.reassigned(),
                r.speculative(), r.lateIgnored(), null);
    }

    @PostMapping("/master/reachability")
    public ReachReport reachability(@RequestBody GraphJob job) {
        if (job.source() == null || job.target() == null) {
            throw new IllegalArgumentException("source et target sont obligatoires");
        }
        return master.reachable(Graph.random(job.nodes(), job.edges(), job.seed()), job.source(), job.target(),
                workers(job));
    }

    /** Workers = tous les autres philosophes, limités à {@code job.workers} si précisé. */
    private List<Integer> workers(GraphJob job) {
        List<Integer> others = IntStream.range(0, props.count()).filter(i -> i != props.id()).boxed().toList();
        int wanted = job.workers() == null ? others.size() : job.workers();
        if (wanted < 1 || wanted > others.size()) {
            throw new IllegalArgumentException("workers doit être entre 1 et " + others.size());
        }
        return others.subList(0, wanted);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Partition absente (Worker redémarré) ou aucun Worker disponible. */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
