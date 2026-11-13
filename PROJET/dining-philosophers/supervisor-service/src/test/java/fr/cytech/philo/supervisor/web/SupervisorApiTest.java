package fr.cytech.philo.supervisor.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"supervisor.forks=5", "supervisor.pair-mode=LOCKED"})
@AutoConfigureMockMvc
class SupervisorApiTest {

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void reset() throws Exception {
        mvc.perform(post("/api/forks/reset")).andExpect(status().isNoContent());
    }

    @Test
    void naiveSingleForkFlow() throws Exception {
        mvc.perform(get("/api/forks/2/available")).andExpect(content().string("true"));
        mvc.perform(post("/api/forks/2/acquire").param("philosopher", "2"))
                .andExpect(jsonPath("$.granted").value(true));
        mvc.perform(get("/api/forks/2/available")).andExpect(content().string("false"));
        mvc.perform(post("/api/forks/2/acquire").param("philosopher", "1"))
                .andExpect(jsonPath("$.granted").value(false));
        mvc.perform(post("/api/forks/2/release").param("philosopher", "2"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/forks/2/available")).andExpect(content().string("true"));
    }

    @Test
    void pairFlowRefusesNeighbour() throws Exception {
        mvc.perform(post("/api/pairs/0/acquire")).andExpect(jsonPath("$.granted").value(true));
        mvc.perform(post("/api/pairs/1/acquire")).andExpect(jsonPath("$.granted").value(false));
        mvc.perform(post("/api/pairs/2/acquire")).andExpect(jsonPath("$.granted").value(true));
        mvc.perform(get("/api/forks"))
                .andExpect(jsonPath("$.pairMode").value("LOCKED"))
                .andExpect(jsonPath("$.owners[0]").value(0))
                .andExpect(jsonPath("$.owners[2]").value(2))
                .andExpect(jsonPath("$.violations").value(0));
    }

    @Test
    void outOfRangeIdGives400() throws Exception {
        mvc.perform(post("/api/pairs/9/acquire")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/forks/-1/available")).andExpect(status().isBadRequest());
    }
}
