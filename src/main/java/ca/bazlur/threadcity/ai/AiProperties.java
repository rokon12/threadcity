package ca.bazlur.threadcity.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validated AI configuration. The API key is deliberately redacted from diagnostics.
 */
@Validated
@ConfigurationProperties("threadcity.ai")
public record AiProperties(
        boolean enabled,
        @NotBlank String provider,
        String apiKey,
        @NotBlank String model,
        @Valid @NotNull Limits limits,
        String killSwitchFile) {

    public AiProperties {
        apiKey = apiKey == null ? "" : apiKey;
    }

    @Override
    public String toString() {
        return "AiProperties[enabled=" + enabled + ", provider=" + provider
                + ", apiKey=<redacted>, model=" + model + ", limits=" + limits
                + ", killSwitchFile=" + killSwitchFile + "]";
    }

    public record Limits(
            @Min(1) int sessionDaily,
            @Min(1) int networkHourly,
            @Min(1) int globalDaily,
            @Min(1) int concurrent,
            String stateFile) {
    }
}
