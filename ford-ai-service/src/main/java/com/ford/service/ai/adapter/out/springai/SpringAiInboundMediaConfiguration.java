package com.ford.service.ai.adapter.out.springai;

import com.ford.service.ai.application.InboundMediaInterpretationPort;
import com.ford.service.ai.configuration.AiProperties;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.HashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class SpringAiInboundMediaConfiguration {
    @Bean("inboundMediaTaskExecutor")
    TaskExecutor inboundMediaTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("inbound-media-");
        executor.initialize();
        return executor;
    }

    @Bean
    InboundMediaInterpretationPort inboundMediaInterpretationPort(
            AiProperties properties, ObjectProvider<OpenAiChatModel> cloudModel,
            ObjectProvider<OllamaChatModel> localModel,
            ObjectProvider<TranscriptionModel> transcriptionModel) {
        var imageModels = new HashMap<String, ChatModel>();
        var cloud = cloudModel.getIfAvailable();
        if (cloud != null) imageModels.put(properties.cloudChatProvider(), cloud);
        var local = localModel.getIfAvailable();
        if (local != null) imageModels.put(properties.localChatProvider(), local);

        var audioModels = new HashMap<String, TranscriptionModel>();
        var transcription = transcriptionModel.getIfAvailable();
        if (transcription != null) audioModels.put(properties.cloudChatProvider(), transcription);
        return new SpringAiInboundMediaInterpretationAdapter(imageModels, audioModels);
    }
}
