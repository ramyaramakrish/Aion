package com.aion.service;

import com.aion.config.AionProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Service
public class OutputWriterService {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final AionProperties properties;
    private final ObjectMapper objectMapper;

    public OutputWriterService(AionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper.copy()
                .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA)
                .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
                .enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);
    }

    /** Pretty-prints JSON and persists .json + .docx under configured Output folder. */
    public OutputPaths write(String jsonObject) throws IOException {
        JsonNode tree = objectMapper.readTree(jsonObject);
        String pretty = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);

        Path dir = properties.outputDir().toAbsolutePath().normalize();
        Files.createDirectories(dir);

        String base = "api-spec-" + STAMP.format(Instant.now());
        Path jsonPath = dir.resolve(base + ".json");
        Files.writeString(jsonPath, pretty, StandardCharsets.UTF_8);

        Path docxPath = dir.resolve(base + ".docx");
        writeWordWithJson(docxPath, pretty);

        return new OutputPaths(jsonPath, docxPath);
    }

    private void writeWordWithJson(Path docxPath, String prettyJson) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.LEFT);
            XWPFRun titleRun = title.createRun();
            titleRun.setBold(true);
            titleRun.setFontSize(16);
            titleRun.setText("API specification (JSON)");

            XWPFParagraph sub = doc.createParagraph();
            XWPFRun subRun = sub.createRun();
            subRun.setItalic(true);
            subRun.setFontSize(10);
            subRun.setText("Generated from requirement documents via Google Gemini AI.");

            XWPFParagraph body = doc.createParagraph();
            XWPFRun run = body.createRun();
            run.setFontFamily("Consolas");
            run.setFontSize(9);
            run.setText(prettyJson);

            try (var out = Files.newOutputStream(docxPath)) {
                doc.write(out);
            }
        }
    }

    public record OutputPaths(Path jsonPath, Path docxPath) {}
}
