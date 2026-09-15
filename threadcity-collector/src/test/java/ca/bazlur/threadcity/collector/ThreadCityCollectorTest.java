package ca.bazlur.threadcity.collector;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThreadCityCollectorTest {

    @Test
    void parsesACompleteCaptureRequest() {
        ThreadCityCollector.Options options = ThreadCityCollector.Options.parse(new String[]{
                "--pid", "1234", "--duration", "30", "--snapshots", "5",
                "--output", "checkout.threadcity"
        });

        assertEquals("1234", options.pid());
        assertEquals(30, options.durationSeconds());
        assertEquals(5, options.snapshots());
        assertEquals(Path.of("checkout.threadcity"), options.output());
    }

    @Test
    void rejectsUnsafeOrUnboundedOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> ThreadCityCollector.Options.parse(new String[]{"--pid", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> ThreadCityCollector.Options.parse(new String[]{"--pid", "1", "--snapshots", "6"}));
        assertThrows(IllegalArgumentException.class,
                () -> ThreadCityCollector.Options.parse(new String[]{"--pid", "1", "--output", "capture.zip"}));
    }
}
