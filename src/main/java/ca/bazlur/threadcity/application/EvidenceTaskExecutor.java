package ca.bazlur.threadcity.application;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds memory-intensive evidence work while letting Vaadin request threads return promptly.
 */
@Service
public final class EvidenceTaskExecutor {

    private static final int MAX_CONCURRENT_ANALYSES = 2;

    private final Semaphore capacity = new Semaphore(MAX_CONCURRENT_ANALYSES, true);
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("threadcity-evidence-", 0).factory());

    public Optional<Future<?>> trySubmit(Runnable task) {
        if (!capacity.tryAcquire()) {
            return Optional.empty();
        }
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                capacity.release();
            }
        };
        FutureTask<Void> future = new FutureTask<>(() -> {
            started.set(true);
            try {
                task.run();
            } finally {
                release.run();
            }
        }, null) {
            @Override
            protected void done() {
                if (!started.get()) {
                    release.run();
                }
            }
        };
        try {
            executor.execute(future);
            return Optional.of(future);
        } catch (RuntimeException exception) {
            release.run();
            throw exception;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
