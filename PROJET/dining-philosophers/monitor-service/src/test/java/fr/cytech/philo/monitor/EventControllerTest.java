package fr.cytech.philo.monitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EventControllerTest {

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void reset() throws Exception {
        mvc.perform(delete("/api/events")).andExpect(status().isNoContent());
    }

    @Test
    void postedEventShowsUpInState() throws Exception {
        mvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"philosopherId": 3, "type": "EATING", "lamport": 7}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sequence").value(1));

        mvc.perform(get("/api/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.philosophers[3].state").value("EATING"))
                .andExpect(jsonPath("$.philosophers[3].lastLamport").value(7));
    }

    @Test
    void invalidPhilosopherGives400() throws Exception {
        mvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"philosopherId": 42, "type": "HUNGRY", "lamport": 1}
                                """))
                .andExpect(status().isBadRequest());
    }
}
