package ca.bazlur.threadcity.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    AiClientIdentityResolver aiClientIdentityResolver() {
        return new AiClientIdentityResolver();
    }

    @Bean
    AiUsageGuard aiUsageGuard(
            Clock clock,
            AiClientIdentityResolver identityResolver,
            AiProperties properties) {
        AiProperties.Limits limits = properties.limits();
        return new AiUsageGuard(
                clock,
                identityResolver,
                properties.enabled(),
                limits.sessionDaily(),
                limits.networkHourly(),
                limits.globalDaily(),
                limits.concurrent(),
                optionalPath(limits.stateFile()),
                optionalPath(properties.killSwitchFile()));
    }

    @Bean
    IncidentExplanationService incidentExplanationService(
            AiUsageGuard usageGuard,
            AiProperties properties) {
        IncidentExplanationService providerService = switch (properties.provider().strip().toLowerCase(Locale.ROOT)) {
            case "openai" -> createOpenAiService(properties.apiKey(), properties.model().strip());
            case "none", "disabled" -> new UnavailableIncidentExplanationService();
            default -> throw new IllegalArgumentException(
                    "Unsupported ThreadCity AI provider: " + properties.provider());
        };
        return providerService.isAvailable()
                ? new GuardedIncidentExplanationService(providerService, usageGuard)
                : providerService;
    }

    private static IncidentExplanationService createOpenAiService(String apiKey, String modelName) {
        if (apiKey == null || apiKey.isBlank()) {
            return new UnavailableIncidentExplanationService();
        }

        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey.strip())
                .modelName(modelName)
                .maxCompletionTokens(700)
                .timeout(Duration.ofSeconds(20))
                .maxRetries(1)
                .logRequests(false)
                .logResponses(false)
                .build();
        StreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey.strip())
                .modelName(modelName)
                .maxCompletionTokens(700)
                .timeout(Duration.ofSeconds(20))
                .logRequests(false)
                .logResponses(false)
                .build();
        return new LangChainIncidentExplanationService(chatModel, streamingChatModel, "OpenAI");
    }

    private static Path optionalPath(String value) {
        return value == null || value.isBlank() ? null : Path.of(value.strip());
    }
}
