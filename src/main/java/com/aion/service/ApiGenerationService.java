package com.aion.service;

import com.aion.service.RagIngestionService.ParsedUpload;
import com.aion.service.OutputWriterService.OutputPaths;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ApiGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ApiGenerationService.class);
    private static final int MAX_TEXT_CHARS_PER_FILE = 30_000;
    private static final int MAX_INLINE_CONTEXT_CHARS = 12_000;

    private final DocumentParsingService documentParsingService;
    private final RagIngestionService ragIngestionService;
    private final ApiSpecAgentService apiSpecAgentService;
    private final OutputWriterService outputWriterService;
    private final ObjectMapper objectMapper;

    public ApiGenerationService(
            DocumentParsingService documentParsingService,
            RagIngestionService ragIngestionService,
            ApiSpecAgentService apiSpecAgentService,
            OutputWriterService outputWriterService,
            ObjectMapper objectMapper) {
        this.documentParsingService = documentParsingService;
        this.ragIngestionService = ragIngestionService;
        this.apiSpecAgentService = apiSpecAgentService;
        this.outputWriterService = outputWriterService;
        this.objectMapper = objectMapper;
    }

    public GenerationResult generate(MultipartFile[] files, boolean fastMode) throws IOException {
        Instant startedAt = Instant.now();
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("Select at least one document");
        }

        List<ParsedUpload> uploads = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
            String text = documentParsingService.extractText(file);
            if (text.length() > MAX_TEXT_CHARS_PER_FILE) {
                log.info("Truncating {} from {} chars to {} chars for faster processing", name, text.length(), MAX_TEXT_CHARS_PER_FILE);
                text = text.substring(0, MAX_TEXT_CHARS_PER_FILE);
            }
            uploads.add(new ParsedUpload(name, text));
            names.add(name);
        }
        if (uploads.isEmpty()) {
            throw new IllegalArgumentException("No non-empty files to process");
        }

        Instant llmStartedAt = Instant.now();
        String specJson;
        if (fastMode) {
            String inlineContext = buildInlineContext(uploads, MAX_INLINE_CONTEXT_CHARS);
            log.info("Using fast mode (single-pass, no embeddings) with {} file(s), {} chars of context",
                    uploads.size(), inlineContext.length());
            specJson = generateSpecFromInlineContextWithHelpfulErrors(String.join(", ", names), inlineContext);
        } else {
            log.info("Using high-quality RAG mode with {} file(s): {}", uploads.size(), String.join(", ", names));
            Instant indexingStartedAt = Instant.now();
            var vectorStore = buildIndexWithHelpfulErrors(uploads);
            log.info("Indexing finished in {}s", Duration.between(indexingStartedAt, Instant.now()).toSeconds());
            specJson = generateSpecWithHelpfulErrors(vectorStore, String.join(", ", names));
        }
        log.info("LLM generation finished in {}s", Duration.between(llmStartedAt, Instant.now()).toSeconds());

        JsonNode node = parseWithRepair(specJson);
        if (!hasRequiredTopLevelKeys(node)) {
            log.warn("Model output missing required top-level keys; attempting schema repair pass");
            specJson = apiSpecAgentService.repairApiSpecJsonToSchema(specJson);
            node = parseWithRepair(specJson);
            if (!hasRequiredTopLevelKeys(node)) {
                throw new IllegalStateException(
                        "Model output could not be normalized to required schema. Try toggling mode and re-run."
                );
            }
        }

        OutputPaths paths = outputWriterService.write(specJson);
        log.info("Total generation completed in {}s", Duration.between(startedAt, Instant.now()).toSeconds());
        return new GenerationResult(true, paths.jsonPath().toString(), paths.docxPath().toString(), specJson);
    }

    private org.springframework.ai.vectorstore.VectorStore buildIndexWithHelpfulErrors(List<ParsedUpload> uploads) {
        try {
            return ragIngestionService.buildIndex(uploads);
        } catch (RuntimeException e) {
            if (isEmbedApiError(e)) {
                throw new IllegalStateException(
                        "Could not call Ollama embedding API at http://localhost:11434/api/embed. " +
                                "Ensure Ollama is running and the model exists: ollama pull nomic-embed-text.",
                        e
                );
            }
            throw e;
        }
    }

    private String generateSpecWithHelpfulErrors(org.springframework.ai.vectorstore.VectorStore vectorStore, String filenamesJoined) {
        try {
            return apiSpecAgentService.generateApiSpecJson(vectorStore, filenamesJoined);
        } catch (RuntimeException e) {
            if (isChatApiError(e)) {
                throw new IllegalStateException(
                        "Could not call Ollama chat API at http://localhost:11434. " +
                                "Ensure Ollama is running and the model exists: ollama pull llama3.2:1b.",
                        e
                );
            }
            throw e;
        }
    }

    private String generateSpecFromInlineContextWithHelpfulErrors(String filenamesJoined, String inlineContext) {
        try {
            return apiSpecAgentService.generateApiSpecJsonFromInlineContext(filenamesJoined, inlineContext);
        } catch (RuntimeException e) {
            if (isChatApiError(e)) {
                throw new IllegalStateException(
                        "Could not call Ollama chat API at http://localhost:11434. " +
                                "Ensure Ollama is running and the model exists: ollama pull llama3.2:1b.",
                        e
                );
            }
            throw e;
        }
    }

    private String shrinkForInlinePrompt(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int front = (int) (maxChars * 0.75);
        int tail = maxChars - front;
        return text.substring(0, front)
                + "\n\n...[content truncated for speed]...\n\n"
                + text.substring(text.length() - tail);
    }

    private String buildInlineContext(List<ParsedUpload> uploads, int maxChars) {
        StringBuilder context = new StringBuilder();
        for (ParsedUpload upload : uploads) {
            context.append("### FILE: ").append(upload.filename()).append('\n')
                    .append(upload.text()).append("\n\n");
            if (context.length() >= maxChars) {
                break;
            }
        }
        return shrinkForInlinePrompt(context.toString(), maxChars);
    }

    private boolean isEmbedApiError(Throwable error) {
        String message = flattenMessage(error).toLowerCase();
        return message.contains("/api/embed")
                || message.contains("embedding")
                || message.contains("connection refused")
                || message.contains("i/o error on post request");
    }

    private boolean hasRequiredTopLevelKeys(JsonNode node) {
        return node.has("meta")
                && node.has("summary")
                && node.has("apis")
                && node.has("dataModels")
                && node.has("nonFunctional");
    }

    private JsonNode parseWithRepair(String candidateJson) throws IOException {
        try {
            return objectMapper.readTree(candidateJson);
        } catch (JsonProcessingException malformed) {
            log.warn("Model output is malformed JSON; attempting repair pass: {}", malformed.getOriginalMessage());
            String repaired = apiSpecAgentService.repairApiSpecJsonToSchema(candidateJson);
            try {
                return objectMapper.readTree(repaired);
            } catch (JsonProcessingException stillMalformed) {
                throw new IllegalStateException(
                        "Model output was malformed and could not be repaired. Try re-running in High-quality RAG mode.",
                        stillMalformed
                );
            }
        }
    }

    private boolean isChatApiError(Throwable error) {
        String message = flattenMessage(error).toLowerCase();
        return message.contains("/api/chat")
                || message.contains("/api/generate")
                || message.contains("chat")
                || message.contains("connection refused")
                || message.contains("i/o error on post request");
    }

    private String flattenMessage(Throwable error) {
        StringBuilder builder = new StringBuilder();
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor.getMessage() != null) {
                builder.append(cursor.getMessage()).append(' ');
            }
            cursor = cursor.getCause();
        }
        return builder.toString();
    }

    public record GenerationResult(boolean ok, String jsonPath, String wordPath, String specJson) {}
}
