package fr.cytech.hamoudilitim.philo.monitor;

import fr.cytech.hamoudilitim.philo.common.PhilosopherEvent;
import fr.cytech.hamoudilitim.philo.monitor.MonitorModel.DashboardSnapshot;
import fr.cytech.hamoudilitim.philo.monitor.MonitorModel.RecordedEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class EventController {

    private final EventStore store;

    public EventController(EventStore store) {
        this.store = store;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RecordedEvent receive(@RequestBody PhilosopherEvent event) {
        return store.record(event);
    }

    @GetMapping("/events")
    public List<RecordedEvent> history(@RequestParam(defaultValue = "200") int limit) {
        return store.history(limit);
    }

    @GetMapping("/state")
    public DashboardSnapshot state() {
        return store.snapshot();
    }

    @DeleteMapping("/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset() {
        store.reset();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
