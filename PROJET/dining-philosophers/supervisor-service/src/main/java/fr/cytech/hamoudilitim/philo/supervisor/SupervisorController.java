package fr.cytech.hamoudilitim.philo.supervisor;

import fr.cytech.hamoudilitim.philo.supervisor.fork.ForkTable;
import fr.cytech.hamoudilitim.philo.supervisor.fork.NaiveForkService;
import fr.cytech.hamoudilitim.philo.supervisor.fork.PairStrategy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API HTTP du superviseur.
 * <ul>
 *   <li>{@code /api/forks/...} — partie 2 : fourchette par fourchette (superviseur naïf) ;</li>
 *   <li>{@code /api/pairs/...} — parties 3.1 / 3.2 : les deux fourchettes en une requête. La stratégie
 *       (UNSAFE ou LOCKED) est choisie par la propriété {@code supervisor.pair-mode}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class SupervisorController {

    public record GrantResponse(boolean granted) {
    }

    public record ForksView(String pairMode, List<Integer> owners, long violations) {
    }

    private final NaiveForkService naive;
    private final PairStrategy pairStrategy;
    private final ForkTable table;

    public SupervisorController(NaiveForkService naive, PairStrategy pairStrategy, ForkTable table) {
        this.naive = naive;
        this.pairStrategy = pairStrategy;
        this.table = table;
    }

    @GetMapping("/forks")
    public ForksView all() {
        return new ForksView(pairStrategy.name(), table.snapshot(), table.violations());
    }

    @PostMapping("/forks/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset() {
        table.reset();
    }

    // ------------------------------------------------------------------ partie 2 : fourchette par fourchette

    @GetMapping("/forks/{fork}/available")
    public boolean available(@PathVariable int fork) {
        return naive.isAvailable(fork);
    }

    @PostMapping("/forks/{fork}/acquire")
    public GrantResponse acquire(@PathVariable int fork, @RequestParam int philosopher) {
        return new GrantResponse(naive.acquire(fork, philosopher));
    }

    @PostMapping("/forks/{fork}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable int fork, @RequestParam int philosopher) {
        naive.release(fork, philosopher);
    }

    // ------------------------------------------------------------------ parties 3.1 / 3.2 : les deux ou rien

    @PostMapping("/pairs/{philosopher}/acquire")
    public GrantResponse acquirePair(@PathVariable int philosopher) {
        table.checkIndex(philosopher);
        return new GrantResponse(pairStrategy.tryAcquire(philosopher));
    }

    @PostMapping("/pairs/{philosopher}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releasePair(@PathVariable int philosopher) {
        table.checkIndex(philosopher);
        pairStrategy.release(philosopher);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
