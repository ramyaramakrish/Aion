package com.aion.service;

import com.aion.config.AionProperties;
import com.aion.util.JsonSpecExtractor;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class GeminiFallbackService {

    private final AionProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private static final Logger logger = LoggerFactory.getLogger(GeminiFallbackService.class);

    private static final String API_SPEC_PROMPT_TEMPLATE = """
            You are a senior integration architect. Your job is to read the provided business and technical inputs \
            and produce a single, valid JSON object describing REST-style API specifications for the solution.

            Rules:
            - Output MUST be one JSON object only, no markdown fences, no commentary before or after.
            - Base every field on the provided context.
            - You MUST replace all placeholder values (like "string", "string semver style", etc.) with concrete values from the context.
            - If a value for a field cannot be found in the context, use null for single fields or an empty array [] for list fields.
            - Prefer concrete resource names, HTTP methods, paths, and field types suggested by the context.
            - Use the exact JSON shape described below. All keys must be present.

            JSON Structure:
            {
              "meta": {
                "title": "string",
                "version": "string semver style",
                "sources": [ "string filenames" ]
              },
              "summary": "string executive summary of the API scope",
              "apis": [
                {
                  "name": "string logical API name",
                  "basePath": "string e.g. /api/v1/orders",
                  "description": "string",
                  "operations": [
                    {
                      "method": "GET|POST|PUT|PATCH|DELETE",
                      "path": "string relative path",
                      "summary": "string",
                      "request": {
                        "contentType": "string",
                        "queryParams": [ { "name": "string", "type": "string", "required": true, "description": "string" } ],
                        "pathParams": [ { "name": "string", "type": "string", "description": "string" } ],
                        "bodySchema": { "description": "string", "exampleFields": { } }
                      },
                      "responses": {
                        "200": { "description": "string", "bodySchema": { } }
                      },
                      "errors": [ { "status": 400, "description": "string" } ]
                    }
                  ]
                }
              ],
              "dataModels": [
                {
                  "name": "string entity name",
                  "description": "string",
                  "fields": [
                    { "name": "string", "type": "string", "required": true, "constraints": "string or null", "source": "string document hint" }
                  ]
                }
              ],
              "nonFunctional": {
                "security": "string or null",
                "rateLimiting": "string or null",
                "observability": "string or null"
              }
            }

            Source filenames: %s
            Context from documents:
            %s
            """;

    public GeminiFallbackService(AionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        if (properties.geminiApiKey() == null || properties.geminiApiKey().isBlank()) {
            logger.warn("**********************************************************************************");
            logger.warn("*** Aion is not configured to use Gemini AI.                                   ***");
            logger.warn("*** Please set the GEMINI_API_KEY environment variable to enable the fallback. ***");
            logger.warn("**********************************************************************************");
        }
    }

    public String answerFromGeneralKnowledge(String question) {
        String prompt = """
                Answer the user question using general knowledge.
                Output in Markdown with:
                - A short heading
                - Bullet points for key facts
                - A brief closing note if there are assumptions.

                User question:
                %s
                """.formatted(question);
        return callGemini(prompt, false);
    }

    public String generateApiSpecFromContext(String context, String filenames) {
        String prompt = API_SPEC_PROMPT_TEMPLATE.formatted(filenames, context);
        return callGemini(prompt, true);
    }

    // --- Gemini API Models ---
    private record GeminiPart(String text) {}
    private record GeminiContent(List<GeminiPart> parts) {}
    private record GenerationConfig(Double temperature) {}
    private record GeminiRequest(
            GeminiContent systemInstruction,
            List<GeminiContent> contents,
            GenerationConfig generationConfig
    ) {}

    private record GeminiCandidate(GeminiContent content) {}
    private record GeminiResponse(List<GeminiCandidate> candidates) {
        public String getFirstText() {
            if (candidates != null && !candidates.isEmpty() &&
                candidates.get(0).content() != null &&
                candidates.get(0).content().parts() != null &&
                !candidates.get(0).content().parts().isEmpty()) {
                return candidates.get(0).content().parts().get(0).text();
            }
            return null;
        }
    }

    private String callGemini(String prompt, boolean extractJson) {
        String apiKey = properties.geminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Required configuration 'GEMINI_API_KEY' is not set. Cannot call Gemini API.");
        }
        String model = properties.geminiModel();
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"
                .formatted(model, apiKey);

        try {
            GeminiContent systemInstruction = new GeminiContent(List.of(new GeminiPart(properties.agentSystemPrompt())));
            GenerationConfig generationConfig = new GenerationConfig(properties.agentTemperature());

            GeminiRequest requestBody = new GeminiRequest(
                    systemInstruction,
                    List.of(new GeminiContent(List.of(new GeminiPart(prompt)))),
                    generationConfig
            );
            String requestJson = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .timeout(Duration.ofMinutes(2))
                    .build();

            logger.debug("Calling Gemini endpoint {}", endpoint);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.warn("Gemini API returned non-2xx status {}: {}", response.statusCode(), responseBody);
                throw new IllegalStateException("Gemini API call failed with status " + response.statusCode());
            }

            GeminiResponse geminiResponse = objectMapper.readValue(responseBody, GeminiResponse.class);
            String text = geminiResponse.getFirstText();
            if (text == null || text.isBlank()) {
                logger.warn("Gemini API returned empty text node; full response: {}", responseBody);
                throw new IllegalStateException("Gemini API returned an empty response.");
            }

            return extractJson ? JsonSpecExtractor.extractJsonObject(text) : text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to call Gemini API", e);
        } catch (IOException e) {
            logger.error("IOException while calling Gemini API", e);
            throw new IllegalStateException("Failed to call Gemini API: " + e.getMessage() + "\n(request payload: see logs)", e);
        }
    }
}
