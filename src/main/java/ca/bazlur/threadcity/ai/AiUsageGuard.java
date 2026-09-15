package ca.bazlur.threadcity.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enforces local hard ceilings before a paid provider request can begin.
 */
final class AiUsageGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiUsageGuard.class);
    private static final int MAX_TRACKED_CLIENTS = 4_096;

    private final Clock clock;
    private final AiClientIdentityResolver identityResolver;
    private final boolean enabled;
    private final int sessionDailyLimit;
    private final int networkHourlyLimit;
    private final int globalDailyLimit;
    private final Semaphore concurrency;
    private final Path stateFile;
    private final Path killSwitchFile;
    private final Map<String, WindowCounter> sessions = new HashMap<>();
    private final Map<String, WindowCounter> networks = new HashMap<>();

    private LocalDate globalDate;
    private int globalCount;
    private volatile boolean persistenceHealthy = true;

    AiUsageGuard(
            Clock clock,
            AiClientIdentityResolver identityResolver,
            boolean enabled,
            int sessionDailyLimit,
            int networkHourlyLimit,
            int globalDailyLimit,
            int concurrentLimit,
            Path stateFile,
            Path killSwitchFile) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.enabled = enabled;
        this.sessionDailyLimit = positive(sessionDailyLimit, "sessionDailyLimit");
        this.networkHourlyLimit = positive(networkHourlyLimit, "networkHourlyLimit");
        this.globalDailyLimit = positive(globalDailyLimit, "globalDailyLimit");
        this.concurrency = new Semaphore(positive(concurrentLimit, "concurrentLimit"), true);
        this.stateFile = stateFile;
        this.killSwitchFile = killSwitchFile;
        loadPersistentState();
    }

    boolean isOperational() {
        return enabled && persistenceHealthy && (killSwitchFile == null || Files.notExists(killSwitchFile));
    }

    synchronized Permit acquire() {
        return acquire(identityResolver.resolve());
    }

    synchronized Permit acquire(AiClientIdentityResolver.AiClientIdentity identity) {
        if (!isOperational()) {
            throw rejection(
                    "AI is disabled or its usage ledger is unavailable",
                    "The AI demo is safely disabled. ThreadCity's deterministic analysis remains available.");
        }

        Instant now = clock.instant();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        long hour = now.getEpochSecond() / 3_600L;
        resetGlobalDay(today);

        WindowCounter session = current(sessions.get(identity.sessionKey()), today.toEpochDay());
        WindowCounter network = current(networks.get(identity.networkKey()), hour);
        if (session.count() >= sessionDailyLimit) {
            throw rejection(
                    "Per-session daily AI limit reached",
                    "This browser has used today's AI demo allowance. Deterministic evidence remains available.");
        }
        if (network.count() >= networkHourlyLimit) {
            throw rejection(
                    "Per-network hourly AI limit reached",
                    "This network has reached the hourly AI demo limit. Please try again later.");
        }
        if (globalCount >= globalDailyLimit) {
            throw rejection(
                    "Global daily AI limit reached",
                    "ThreadCity has reached today's protected AI allowance. Deterministic analysis remains available.");
        }
        if (!concurrency.tryAcquire()) {
            throw rejection(
                    "Concurrent AI limit reached",
                    "The AI copilot is busy protecting its capacity. Please try again in a moment.");
        }

        try {
            persist(today, globalCount + 1);
            globalCount++;
            sessions.put(identity.sessionKey(), new WindowCounter(today.toEpochDay(), session.count() + 1));
            networks.put(identity.networkKey(), new WindowCounter(hour, network.count() + 1));
            prune(sessions, today.toEpochDay());
            prune(networks, hour);
            return new Permit(concurrency);
        } catch (RuntimeException exception) {
            concurrency.release();
            throw exception;
        }
    }

    private void resetGlobalDay(LocalDate today) {
        if (!today.equals(globalDate)) {
            globalDate = today;
            globalCount = 0;
        }
    }

    private void loadPersistentState() {
        globalDate = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (stateFile == null || Files.notExists(stateFile)) {
            return;
        }
        try {
            Map<String, String> values = Files.readAllLines(stateFile, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.split("=", 2))
                    .filter(parts -> parts.length == 2)
                    .collect(java.util.stream.Collectors.toMap(parts -> parts[0], parts -> parts[1]));
            LocalDate storedDate = LocalDate.parse(values.get("date"));
            int storedCount = Integer.parseInt(values.get("count"));
            if (storedCount < 0) {
                throw new IllegalArgumentException("Negative AI usage count");
            }
            if (storedDate.equals(globalDate)) {
                globalCount = storedCount;
            }
        } catch (IOException | RuntimeException exception) {
            persistenceHealthy = false;
            LOGGER.warn("AI usage ledger could not be loaded; provider access is disabled ({})",
                    exception.getClass().getSimpleName());
        }
    }

    private void persist(LocalDate date, int count) {
        if (stateFile == null) {
            return;
        }
        Path parent = stateFile.toAbsolutePath().getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, stateFile.getFileName().toString(), ".tmp");
            Files.writeString(
                    temporary,
                    "date=" + date + System.lineSeparator() + "count=" + count + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(
                        temporary,
                        stateFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            persistenceHealthy = false;
            LOGGER.warn("AI usage ledger could not be persisted; provider access is disabled ({})",
                    exception.getClass().getSimpleName());
            throw rejection(
                    "Could not persist the AI usage ledger",
                    "The AI demo was safely disabled because its usage protection is unavailable.");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    LOGGER.debug("Temporary AI usage-ledger file could not be deleted", ignored);
                }
            }
        }
    }

    private static WindowCounter current(WindowCounter counter, long window) {
        return counter == null || counter.window() != window ? new WindowCounter(window, 0) : counter;
    }

    private static void prune(Map<String, WindowCounter> counters, long currentWindow) {
        if (counters.size() > MAX_TRACKED_CLIENTS) {
            counters.entrySet().removeIf(entry -> entry.getValue().window() != currentWindow);
            var iterator = counters.keySet().iterator();
            while (counters.size() > MAX_TRACKED_CLIENTS && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static AiUsageLimitException rejection(String message, String userMessage) {
        return new AiUsageLimitException(message, userMessage);
    }

    private record WindowCounter(long window, int count) {
    }

    static final class Permit implements AutoCloseable {

        private final Semaphore concurrency;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(Semaphore concurrency) {
            this.concurrency = concurrency;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                concurrency.release();
            }
        }
    }
}
