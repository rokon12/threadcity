package ca.bazlur.threadcity.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiUsageGuardTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-14T16:00:00Z"), ZoneOffset.UTC);
    private static final AiClientIdentityResolver.AiClientIdentity CLIENT =
            new AiClientIdentityResolver.AiClientIdentity("session-a", "network-a");

    @TempDir
    Path temporaryDirectory;

    @Test
    void enforcesThePerSessionDailyLimit() {
        AiUsageGuard guard = guard(2, 10, 10, 1, null, null);

        guard.acquire(CLIENT).close();
        guard.acquire(CLIENT).close();

        assertThatThrownBy(() -> guard.acquire(CLIENT))
                .isInstanceOf(AiUsageLimitException.class)
                .hasMessageContaining("Per-session");
    }

    @Test
    void enforcesConcurrencyAndReleasesCapacityOnClose() {
        AiUsageGuard guard = guard(10, 10, 10, 1, null, null);
        AiUsageGuard.Permit first = guard.acquire(CLIENT);

        assertThatThrownBy(() -> guard.acquire(new AiClientIdentityResolver.AiClientIdentity(
                "session-b", "network-b")))
                .isInstanceOf(AiUsageLimitException.class)
                .hasMessageContaining("Concurrent");

        first.close();
        guard.acquire(new AiClientIdentityResolver.AiClientIdentity("session-b", "network-b")).close();
    }

    @Test
    void persistsTheGlobalDailyCeilingAcrossRestarts() {
        Path stateFile = temporaryDirectory.resolve("ai-usage.properties");
        AiUsageGuard firstProcess = guard(10, 10, 2, 1, stateFile, null);
        firstProcess.acquire(CLIENT).close();
        firstProcess.acquire(new AiClientIdentityResolver.AiClientIdentity("session-b", "network-b")).close();

        AiUsageGuard restartedProcess = guard(10, 10, 2, 1, stateFile, null);

        assertThatThrownBy(() -> restartedProcess.acquire(
                new AiClientIdentityResolver.AiClientIdentity("session-c", "network-c")))
                .isInstanceOf(AiUsageLimitException.class)
                .hasMessageContaining("Global daily");
        assertThat(stateFile).content().contains("date=2026-09-14", "count=2");
    }

    @Test
    void aKillSwitchFileDisablesNewRequests() throws IOException {
        Path killSwitch = temporaryDirectory.resolve("AI_DISABLED");
        Files.createFile(killSwitch);
        AiUsageGuard guard = guard(10, 10, 10, 1, null, killSwitch);

        assertThat(guard.isOperational()).isFalse();
        assertThatThrownBy(() -> guard.acquire(CLIENT))
                .isInstanceOf(AiUsageLimitException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void aCorruptPersistentLedgerFailsClosed() throws IOException {
        Path stateFile = temporaryDirectory.resolve("ai-usage.properties");
        Files.writeString(stateFile, "not a valid ledger");

        AiUsageGuard guard = guard(10, 10, 10, 1, stateFile, null);

        assertThat(guard.isOperational()).isFalse();
        assertThatThrownBy(() -> guard.acquire(CLIENT))
                .isInstanceOf(AiUsageLimitException.class);
    }

    private static AiUsageGuard guard(
            int sessionDaily,
            int networkHourly,
            int globalDaily,
            int concurrent,
            Path stateFile,
            Path killSwitchFile) {
        return new AiUsageGuard(
                CLOCK,
                new AiClientIdentityResolver(),
                true,
                sessionDaily,
                networkHourly,
                globalDaily,
                concurrent,
                stateFile,
                killSwitchFile);
    }
}
