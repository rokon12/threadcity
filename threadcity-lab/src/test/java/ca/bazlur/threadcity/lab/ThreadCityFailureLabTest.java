package ca.bazlur.threadcity.lab;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThreadCityFailureLabTest {

    @Test
    void parsesDefaultAndExplicitDurations() {
        assertEquals(Duration.ofSeconds(120), ThreadCityFailureLab.parseDuration(new String[0]));
        assertEquals(Duration.ofSeconds(45),
                ThreadCityFailureLab.parseDuration(new String[]{"--duration", "45"}));
    }

    @Test
    void rejectsOutOfRangeOrMalformedDurations() {
        assertThrows(IllegalArgumentException.class,
                () -> ThreadCityFailureLab.parseDuration(new String[]{"--duration", "9"}));
        assertThrows(IllegalArgumentException.class,
                () -> ThreadCityFailureLab.parseDuration(new String[]{"--seconds", "30"}));
    }
}
