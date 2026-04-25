package com.aion.service;

import com.aion.service.OutputWriterService.OutputPaths;
import com.fasterxml.jackson.core.JsonParser;
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
import java.util.Arrays;
import java.util.List;

@Service
public class ApiGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ApiGenerationService.class);

    private final DocumentParsingService documentParsingService;
    private final ApiSpecAgentService apiSpecAgentService;
    private final OutputWriterService outputWriterService;
    private final ObjectMapper objectMapper;

    public ApiGenerationService(
            DocumentParsingService documentParsingService,
            ApiSpecAgentService apiSpecAgentService,
            OutputWriterService outputWriterService,
            ObjectMapper objectMapper) {
        this.documentParsingService = documentParsingService;
        this.apiSpecAgentService = apiSpecAgentService;
        this.outputWriterService = outputWriterService;
        this.objectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA)
                .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
                .enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);
    }

    public GenerationResult generate(MultipartFile[] files) throws IOException {
        Instant startedAt = Instant.now();
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("Select at least one document");
        }

        log.info("Generating API specification using Spring AI Agent for {} file(s).", files.length);

        List<String> names = new ArrayList<>();
        StringBuilder fullContext = new StringBuilder();

        for (MultipartFile file : Arrays.stream(files).filter(f -> f != null && !f.isEmpty()).toList()) {
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
            String text = documentParsingService.extractText(file);
            names.add(name);
            fullContext.append("### FILE: ").append(name).append('\n')
                    .append(text).append("\n\n");
        }

        if (names.isEmpty()) {
            throw new IllegalArgumentException("No non-empty files to process");
        }

        Instant llmStartedAt = Instant.now();
        String filenamesJoined = String.join(", ", names);
        String specJson = apiSpecAgentService.generateApiSpecJsonFromInlineContext(filenamesJoined, fullContext.toString());
        log.info("LLM generation finished in {}s", Duration.between(llmStartedAt, Instant.now()).toSeconds());

        JsonNode node;
        try {
            node = objectMapper.readTree(specJson);
        } catch (JsonProcessingException e) {
            log.warn("Agent output was malformed JSON, attempting repair: {}", specJson);
            specJson = apiSpecAgentService.repairApiSpecJsonToSchema(specJson);
            try {
                node = objectMapper.readTree(specJson);
            } catch (JsonProcessingException ex) {
                log.error("Agent output was malformed JSON even after repair: {}", specJson, ex);
                throw new IllegalStateException("Agent returned a malformed JSON specification.", ex);
            }
        }

        String finalJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        OutputPaths paths = outputWriterService.write(finalJson);
        log.info("Total generation completed in {}s", Duration.between(startedAt, Instant.now()).toSeconds());

        return new GenerationResult(true, paths.jsonPath().toString(), paths.docxPath().toString(), finalJson, "Generation complete! The API specification was successfully created via Gemini AI.");
    }

    public record GenerationResult(boolean ok, String jsonPath, String wordPath, String specJson, String message) {}
}
