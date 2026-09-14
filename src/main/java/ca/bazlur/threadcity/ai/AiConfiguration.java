package ca.bazlur.threadcity.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

@Configuration(proxyBeanMethods = false)
class AiConfiguration {

    @Bean
    AiClientIdentityResolver aiClientIdentityResolver() {
        return new AiClientIdentityResolver();
    }

    @Bean
    AiUsageGuard aiUsageGuard(
            AiClientIdentityResolver identityResolver,
            @Value("${threadcity.ai.enabled:true}") boolean enabled,
            @Value("${threadcity.ai.limits.session-daily:6}") int sessionDailyLimit,
            @Value("${threadcity.ai.limits.network-hourly:12}") int networkHourlyLimit,
            @Value("${threadcity.ai.limits.global-daily:100}") int globalDailyLimit,
            @Value("${threadcity.ai.limits.concurrent:2}") int concurrentLimit,
            @Value("${threadcity.ai.limits.state-file:}") String stateFile,
            @Value("${threadcity.ai.kill-switch-file:}") String killSwitchFile) {
        return new AiUsageGuard(
                Clock.systemUTC(),
                identityResolver,
                enabled,
                sessionDailyLimit,
                networkHourlyLimit,
                globalDailyLimit,
                concurrentLimit,
                optionalPath(stateFile),
                optionalPath(killSwitchFile));
    }

    @Bean
    IncidentExplanationService incidentExplanationService(
            AiUsageGuard usageGuard,
            @Value("${threadcity.ai.provider:none}") String provider,
            @Value("${threadcity.ai.api-key:}") String apiKey,
            @Value("${threadcity.ai.model:gpt-4.1-mini}") String openAiModelName) {
        IncidentExplanationService providerService = switch (provider.strip().toLowerCase(Locale.ROOT)) {
            case "openai" -> createOpenAiService(apiKey, openAiModelName);
            case "none", "disabled" -> new UnavailableIncidentExplanationService();
            default -> throw new IllegalArgumentException("Unsupported ThreadCity AI provider: " + provider);
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
