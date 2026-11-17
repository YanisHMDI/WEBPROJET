package fr.cytech.hamoudilitim.philo.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LamportClockTest {

    @Test
    void tickIncrementsByOne() {
        LamportClock clock = new LamportClock();
        assertThat(clock.tick()).isEqualTo(1);
        assertThat(clock.tick()).isEqualTo(2);
        assertThat(clock.current()).isEqualTo(2);
    }

    @Test
    void receiveTakesMaxPlusOne() {
        LamportClock clock = new LamportClock();
        clock.tick();                              // 1
        assertThat(clock.onReceive(10)).isEqualTo(11);
        assertThat(clock.onReceive(3)).isEqualTo(12); // message "ancien" : on avance quand même
    }

    @Test
    void negativeTimestampIsRejected() {
        assertThatThrownBy(() -> new LamportClock().onReceive(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentTicksNeverLoseUpdates() throws InterruptedException {
        LamportClock clock = new LamportClock();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 10_000; i++) {
            pool.submit(clock::tick);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(clock.current()).isEqualTo(10_000);
    }
}
