package ca.bazlur.threadcity;

import ca.bazlur.threadcity.application.IncidentBundleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ThreadCityApplicationTest {

    @Value("${spring.servlet.multipart.max-file-size}")
    private DataSize multipartFileLimit;

    @Value("${spring.servlet.multipart.max-request-size}")
    private DataSize multipartRequestLimit;

    @Test
    void contextLoads() {
    }

    @Test
    void multipartLimitsAllowTheLargestSupportedIncidentBundle() {
        assertThat(multipartFileLimit.toBytes())
                .isGreaterThanOrEqualTo(IncidentBundleService.MAX_BUNDLE_BYTES);
        assertThat(multipartRequestLimit.toBytes())
                .isGreaterThan(IncidentBundleService.MAX_BUNDLE_BYTES);
    }
}
