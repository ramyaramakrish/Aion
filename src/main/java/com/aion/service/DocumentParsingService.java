package com.aion.service;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Service
public class DocumentParsingService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx"
    );

    private final AutoDetectParser parser = new AutoDetectParser();

    public void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Each selected file must be non-empty");
        }
        String name = file.getOriginalFilename();
        validateFileName(name);
    }

    public void validatePath(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Document path is invalid: " + path);
        }
        validateFileName(path.getFileName() != null ? path.getFileName().toString() : null);
    }

    private void validateFileName(String name) {
        if (name == null || !hasAllowedExtension(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Unsupported file type. Allowed: PDF, Word (.doc/.docx), Excel (.xls/.xlsx): " + name);
        }
    }

    private boolean hasAllowedExtension(String lowerName) {
        return ALLOWED_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
    }

    public String extractText(MultipartFile file) throws IOException {
        validateFile(file);
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        try (InputStream inputStream = file.getInputStream()) {
            parser.parse(inputStream, handler, metadata, context);
        } catch (SAXException | TikaException e) {
            throw new IOException("Could not parse document: " + file.getOriginalFilename(), e);
        }
        String text = handler.toString();
        if (text == null || text.isBlank()) {
            throw new IOException("No extractable text from: " + file.getOriginalFilename());
        }
        return text;
    }

    public String extractText(Path path) throws IOException {
        validatePath(path);
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        try (InputStream inputStream = Files.newInputStream(path)) {
            parser.parse(inputStream, handler, metadata, context);
        } catch (SAXException | TikaException e) {
            throw new IOException("Could not parse document: " + path.getFileName(), e);
        }
        String text = handler.toString();
        if (text == null || text.isBlank()) {
            throw new IOException("No extractable text from: " + path.getFileName());
        }
        return text;
    }
}
