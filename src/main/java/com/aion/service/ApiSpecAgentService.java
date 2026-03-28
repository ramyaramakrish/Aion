package com.aion.service;

import com.aion.util.JsonSpecExtractor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class ApiSpecAgentService {

    private static final String SYSTEM_PROMPT = """
            You are a senior integration architect. Your job is to read business and technical inputs \
            and produce a single, valid JSON object describing REST-style API specifications for the solution.

            Rules:
            - Output MUST be one JSON object only, no markdown fences, no commentary before or after.
            - Base every field on the retrieved context; if something is unknown, use null or empty arrays, never invent unrelated domains.
            - Prefer concrete resource names, HTTP methods, paths, and field types suggested by the BRD, understanding docs, and data dictionaries.
            - Use the exact JSON shape described in the user message. All keys must be present.
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            Retrieved context comes from requirement documents (BRD, understanding notes, Excel data dictionaries).

            Source filenames: %s

            Return JSON with this exact structure (types indicated in comments for you; output valid JSON only):
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

            Fill arrays with as many items as the documents justify; use [] when none apply.
            """;

    private final ChatModel chatModel;

    public ApiSpecAgentService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String generateApiSpecJson(VectorStore vectorStore, String filenamesJoined) {
        var advisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(16)
                        .similarityThreshold(0.2)
                        .build())
                .build();

        String userMessage = USER_PROMPT_TEMPLATE.formatted(filenamesJoined);

        String raw = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .advisors(advisor)
                .call()
                .content();

        return JsonSpecExtractor.extractJsonObject(raw);
    }
}
