package ca.bazlur.threadcity.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class AiConfiguration {

    @Bean
    IncidentExplanationService incidentExplanationService(
            @Value("${threadcity.ai.api-key:}") String apiKey,
            @Value("${threadcity.ai.model:gpt-4.1-mini}") String modelName) {
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
        return new LangChainIncidentExplanationService(chatModel, streamingChatModel);
    }
}
