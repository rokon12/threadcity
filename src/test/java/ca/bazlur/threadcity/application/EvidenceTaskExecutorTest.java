package ca.bazlur.threadcity.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class EvidenceTaskExecutorTest {

    @Test
    void boundsConcurrentWorkAndReturnsCapacityAfterCancellation() throws InterruptedException {
        EvidenceTaskExecutor executor = new EvidenceTaskExecutor();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocking = () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            var first = executor.trySubmit(blocking).orElseThrow();
            executor.trySubmit(blocking).orElseThrow();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.trySubmit(() -> { })).isEmpty();

            first.cancel(true);

            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(executor.trySubmit(() -> { })).isPresent());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
