package fr.cytech.philo.supervisor.web;

import fr.cytech.philo.supervisor.fork.ForkTable;
import fr.cytech.philo.supervisor.fork.NaiveForkService;
import fr.cytech.philo.supervisor.fork.PairStrategy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Partie 2 — API fourchette par fourchette (superviseur naïf).
 */
@RestController
@RequestMapping("/api/forks")
public class ForkController {

    private final NaiveForkService naive;
    private final ForkTable table;
    private final PairStrategy pairStrategy;

    public ForkController(NaiveForkService naive, ForkTable table, PairStrategy pairStrategy) {
        this.naive = naive;
        this.table = table;
        this.pairStrategy = pairStrategy;
    }

    public record ForksView(String pairMode, List<Integer> owners, long violations) {
    }

    @GetMapping
    public ForksView all() {
        return new ForksView(pairStrategy.name(), table.snapshot(), table.violations());
    }

    @GetMapping("/{fork}/available")
    public boolean available(@PathVariable int fork) {
        return naive.isAvailable(fork);
    }

    @PostMapping("/{fork}/acquire")
    public GrantResponse acquire(@PathVariable int fork, @RequestParam int philosopher) {
        return new GrantResponse(naive.acquire(fork, philosopher));
    }

    @PostMapping("/{fork}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable int fork, @RequestParam int philosopher) {
        naive.release(fork, philosopher);
    }

    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset() {
        table.reset();
    }
}
