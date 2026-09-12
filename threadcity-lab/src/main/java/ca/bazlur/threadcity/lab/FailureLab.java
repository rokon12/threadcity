package ca.bazlur.threadcity.lab;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class FailureLab implements AutoCloseable {

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch releaseVirtualThreads = new CountDownLatch(1);
    private final ConnectionPool connectionPool = new ConnectionPool();
    private final List<Socket> acceptedSockets = new ArrayList<>();
    private ExecutorService starvedExecutor;
    private ServerSocket serverSocket;

    void start() throws Exception {
        startDeadlock();
        startLockConvoy();
        startExecutorStarvation();
        startConnectionPoolExhaustion();
        startSocketStall();
        startCpuSpin();
        startVirtualThreadPressure();
        startJfrPulse();
    }

    private void startDeadlock() {
        Object paymentLock = new PaymentLock();
        Object inventoryLock = new InventoryLock();
        CyclicBarrier bothOwnFirstLock = new CyclicBarrier(2);
        startDaemon("lab-deadlock-payment", () -> {
            synchronized (paymentLock) {
                await(bothOwnFirstLock);
                synchronized (inventoryLock) {
                    // Unreachable by design.
                }
            }
        });
        startDaemon("lab-deadlock-inventory", () -> {
            synchronized (inventoryLock) {
                await(bothOwnFirstLock);
                synchronized (paymentLock) {
                    // Unreachable by design.
                }
            }
        });
    }

    private void startLockConvoy() throws InterruptedException {
        Object convoyLock = new CheckoutLock();
        CountDownLatch ownerHoldingLock = new CountDownLatch(1);
        startDaemon("lab-convoy-owner", () -> {
            synchronized (convoyLock) {
                ownerHoldingLock.countDown();
                while (running.get()) {
                    sleep(250);
                }
            }
        });
        ownerHoldingLock.await();
        for (int index = 1; index <= 8; index++) {
            startDaemon("lab-convoy-waiter-" + index, () -> {
                synchronized (convoyLock) {
                    // The owner releases only during shutdown.
                }
            });
        }
    }

    private void startExecutorStarvation() {
        int workers = 3;
        CountDownLatch outerTasksStarted = new CountDownLatch(workers);
        starvedExecutor = Executors.newFixedThreadPool(workers, namedDaemonFactory("lab-starved-worker-"));
        for (int index = 0; index < workers; index++) {
            starvedExecutor.submit(() -> waitForNestedTask(outerTasksStarted));
        }
    }

    private void waitForNestedTask(CountDownLatch outerTasksStarted) {
        outerTasksStarted.countDown();
        await(outerTasksStarted);
        Future<String> nested = starvedExecutor.submit(() -> "This task cannot get a worker");
        try {
            nested.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Cancellation during lab shutdown is expected.
        }
    }

    private void startConnectionPoolExhaustion() {
        for (int index = 1; index <= 6; index++) {
            startDaemon("lab-db-waiter-" + index, () -> {
                try {
                    connectionPool.borrow();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    private void startSocketStall() throws IOException {
        serverSocket = new ServerSocket(0);
        startDaemon("lab-socket-acceptor", () -> {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    synchronized (acceptedSockets) {
                        acceptedSockets.add(socket);
                    }
                } catch (IOException exception) {
                    if (running.get()) {
                        exception.printStackTrace(System.err);
                    }
                    return;
                }
            }
        });
        int port = serverSocket.getLocalPort();
        for (int index = 1; index <= 4; index++) {
            startDaemon("lab-socket-reader-" + index, () -> stalledSocketRead(port));
        }
    }

    private void stalledSocketRead(int port) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getInputStream().read();
        } catch (IOException ignored) {
            // Closing the lab unblocks this deliberate read.
        }
    }

    private void startCpuSpin() {
        for (int index = 1; index <= 2; index++) {
            startDaemon("lab-cpu-spinner-" + index, () -> {
                long value = 1;
                while (running.get()) {
                    value = value * 31 + 17;
                    if ((value & 0x3fff) == 0) {
                        Thread.onSpinWait();
                    }
                }
            });
        }
    }

    private void startVirtualThreadPressure() {
        for (int index = 1; index <= 80; index++) {
            int task = index;
            Thread.ofVirtual().name("lab-virtual-request-" + task).start(() -> {
                PressurePulse.emit("virtual-thread backlog", task, "waiting for the release gate");
                await(releaseVirtualThreads);
            });
        }
    }

    private void startJfrPulse() {
        List<String> scenarios = List.of(
                "deadlock", "lock convoy", "executor starvation", "connection pool", "socket stall", "CPU spin");
        startDaemon("lab-jfr-pulse", () -> {
            int index = 0;
            while (running.get()) {
                String scenario = scenarios.get(index % scenarios.size());
                PressurePulse.emit(scenario, (index % 10) + 1, "deliberate ThreadCity lab signal");
                index++;
                sleep(100);
            }
        });
    }

    private ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> Thread.ofPlatform()
                .name(prefix + sequence.incrementAndGet())
                .daemon(true)
                .unstarted(runnable);
    }

    private void startDaemon(String name, Runnable action) {
        Thread.ofPlatform().name(name).daemon(true).start(action);
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running.set(false);
        releaseVirtualThreads.countDown();
        connectionPool.releaseAll();
        if (starvedExecutor != null) {
            starvedExecutor.shutdownNow();
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // The socket is process-local test data.
            }
        }
        synchronized (acceptedSockets) {
            acceptedSockets.forEach(socket -> {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // The socket is process-local test data.
                }
            });
        }
    }

    private static final class ConnectionPool {
        private final Semaphore available = new Semaphore(0);

        private Object borrow() throws InterruptedException {
            available.acquire();
            return new Object();
        }

        private void releaseAll() {
            available.release(100);
        }
    }

    private static final class PaymentLock {
    }

    private static final class InventoryLock {
    }

    private static final class CheckoutLock {
    }
}
