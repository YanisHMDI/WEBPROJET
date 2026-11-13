package fr.cytech.philo.philosopher;

import fr.cytech.philo.philosopher.core.DiningStrategy;
import fr.cytech.philo.philosopher.strategy.PairSupervisorStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "philo.id=3",
        "philo.count=5",
        "philo.mode=PAIR_SUPERVISOR",
        "philo.auto-start=false"
})
@AutoConfigureMockMvc
class PhilosopherApplicationTest {

    @Autowired
    private DiningStrategy strategy;
    @Autowired
    private MockMvc mvc;

    @Test
    void modeSelectsTheRightStrategy() {
        assertThat(strategy).isInstanceOf(PairSupervisorStrategy.class);
    }

    @Test
    void statusEndpointReportsConfiguration() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.mode").value("PAIR_SUPERVISOR"))
                .andExpect(jsonPath("$.state").value("STOPPED"));
    }
}
