package fr.cytech.philo.supervisor.web;

import fr.cytech.philo.supervisor.fork.ForkTable;
import fr.cytech.philo.supervisor.fork.PairStrategy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parties 3.1 / 3.2 — demande des deux fourchettes en une requête.
 * La stratégie (UNSAFE ou LOCKED) est choisie par la propriété supervisor.pair-mode.
 */
@RestController
@RequestMapping("/api/pairs")
public class PairController {

    private final PairStrategy strategy;
    private final ForkTable table;

    public PairController(PairStrategy strategy, ForkTable table) {
        this.strategy = strategy;
        this.table = table;
    }

    @PostMapping("/{philosopher}/acquire")
    public GrantResponse acquire(@PathVariable int philosopher) {
        table.checkIndex(philosopher);
        return new GrantResponse(strategy.tryAcquire(philosopher));
    }

    @PostMapping("/{philosopher}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable int philosopher) {
        table.checkIndex(philosopher);
        strategy.release(philosopher);
    }
}
