package ca.bazlur.threadcity.ui.component;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LockTrafficMapTest {

    @Test
    void compactConstructorRetainsItsHostClassName() {
        LockTrafficMap map = new LockTrafficMap("time-machine-map", true, ignored -> { });

        assertThat(map.getClassNames()).contains("time-machine-map");
    }
}
