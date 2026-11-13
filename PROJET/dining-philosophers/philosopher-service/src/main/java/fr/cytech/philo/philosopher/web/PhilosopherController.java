package fr.cytech.philo.philosopher.web;

import fr.cytech.philo.philosopher.PhilosopherProperties;
import fr.cytech.philo.philosopher.PhilosopherRunner;
import fr.cytech.philo.philosopher.core.Philosopher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PhilosopherController {

    public record Status(int id, String mode, Philosopher.State state, int meals, long clock) {
    }

    private final Philosopher philosopher;
    private final PhilosopherRunner runner;
    private final PhilosopherProperties props;

    public PhilosopherController(Philosopher philosopher, PhilosopherRunner runner, PhilosopherProperties props) {
        this.philosopher = philosopher;
        this.runner = runner;
        this.props = props;
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(philosopher.id(), props.mode().name(), philosopher.state(),
                philosopher.meals(), philosopher.clock());
    }

    @PostMapping("/start")
    public Status start() {
        runner.start();
        return status();
    }

    @PostMapping("/stop")
    public Status stop() {
        runner.stop();
        return status();
    }
}
