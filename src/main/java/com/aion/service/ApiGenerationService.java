package com.aion.service;

import com.aion.service.RagIngestionService.ParsedUpload;
import com.aion.service.OutputWriterService.OutputPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class ApiGenerationService {

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

    public GenerationResult generate(MultipartFile[] files) throws IOException {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("Select at least one document");
        }

        List<ParsedUpload> uploads = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String text = documentParsingService.extractText(file);
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
            uploads.add(new ParsedUpload(name, text));
            names.add(name);
        }
        if (uploads.isEmpty()) {
            throw new IllegalArgumentException("No non-empty files to process");
        }

        var vectorStore = ragIngestionService.buildIndex(uploads);
        String specJson = apiSpecAgentService.generateApiSpecJson(vectorStore, String.join(", ", names));

        JsonNode node = objectMapper.readTree(specJson);
        if (!node.has("meta") || !node.has("apis")) {
            throw new IllegalStateException("Model output missing required top-level keys");
        }

        OutputPaths paths = outputWriterService.write(specJson);
        return new GenerationResult(true, paths.jsonPath().toString(), paths.docxPath().toString(), specJson);
    }

    public record GenerationResult(boolean ok, String jsonPath, String wordPath, String specJson) {}
}
