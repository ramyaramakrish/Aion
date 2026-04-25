package com.aion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "aion")
public record AionProperties(
        Path outputDir,
        Path documentsDir,
        Double docBuddySimilarityThreshold,
        String geminiApiKey,
        String geminiModel,
        Double agentTemperature,
        String agentSystemPrompt
) {
    public AionProperties {
        if (outputDir == null) {
            outputDir = Path.of("Output");
        }
        if (documentsDir == null) {
            documentsDir = Path.of("Documents");
        }
        if (docBuddySimilarityThreshold == null) {
            docBuddySimilarityThreshold = 0.6d;
        }
        if (geminiModel == null || geminiModel.isBlank()) {
            geminiModel = "gemini-2.5-flash";
        }
        if (agentTemperature == null) {
            agentTemperature = 0.7d;
        }
        if (agentSystemPrompt == null || agentSystemPrompt.isBlank()) {
            agentSystemPrompt = "You are an intelligent assistant for the Aion platform.";
        }
    }
}
