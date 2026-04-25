package com.aion.service;

import com.aion.config.AionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@Service
public class GeminiFallbackService {

    private final AionProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private static final Logger logger = LoggerFactory.getLogger(GeminiFallbackService.class);

    public GeminiFallbackService(AionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    public String answerFromGeneralKnowledge(String question) {
        String apiKey = properties.geminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Gemini fallback is not configured. Set GEMINI_API_KEY environment variable.");
        }

        String model = properties.geminiModel();
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"
                .formatted(model, apiKey);

        String prompt = """
                Answer the user question using general knowledge.
                Output in Markdown with:
                - A short heading
                - Bullet points for key facts
                - A brief closing note if there are assumptions.

                User question:
                %s
                """.formatted(question);

        try {
        // Build request using a "prompt": { "text": ... } shape which matches
        // the v1beta generateContent examples in some deployments.
        String requestJson = objectMapper.createObjectNode()
            .putObject("prompt")
            .put("text", prompt)
            .toString();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            logger.debug("Calling Gemini endpoint {} with payload: {}", endpoint, requestJson);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn("Gemini API returned non-2xx status {}: {}", response.statusCode(), responseBody);
                throw new IllegalStateException("Gemini API call failed with status " + response.statusCode() + ": " + responseBody);
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode textNode = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (textNode.isMissingNode() || textNode.asText().isBlank()) {
                logger.warn("Gemini API returned empty text node; full response: {}", response.body());
                throw new IllegalStateException("Gemini API returned an empty response: " + response.body());
            }
            return textNode.asText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to call Gemini API", e);
        } catch (IOException e) {
            logger.error("IOException while calling Gemini API", e);
            throw new IllegalStateException("Failed to call Gemini API: " + e.getMessage() + "\n(request payload: see logs)", e);
        }
    }
}
