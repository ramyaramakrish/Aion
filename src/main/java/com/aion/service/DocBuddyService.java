package com.aion.service;

import com.aion.config.AionProperties;
import com.aion.service.RagIngestionService.ParsedUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Service
public class DocBuddyService {

    private static final Logger log = LoggerFactory.getLogger(DocBuddyService.class);
    private static final int TOP_K = 6;
    private static final List<String> ALLOWED_SUFFIXES = List.of(".pdf", ".doc", ".docx", ".xls", ".xlsx");
    private static final String INSUFFICIENT_CONTEXT_TOKEN = "INSUFFICIENT_CONTEXT";

    private final AionProperties properties;
    private final DocumentParsingService documentParsingService;
    private final RagIngestionService ragIngestionService;
    private final GeminiFallbackService geminiFallbackService;
    private final ChatModel chatModel;

    private volatile CachedIndex cachedIndex;

    public DocBuddyService(
            AionProperties properties,
            DocumentParsingService documentParsingService,
            RagIngestionService ragIngestionService,
            GeminiFallbackService geminiFallbackService,
            ChatModel chatModel) {
        this.properties = properties;
        this.documentParsingService = documentParsingService;
        this.ragIngestionService = ragIngestionService;
        this.geminiFallbackService = geminiFallbackService;
        this.chatModel = chatModel;
    }

    public DocBuddyResponse ask(String question) throws IOException {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question cannot be empty");
        }

        CachedIndex index = getOrBuildIndex();
        if (index.store == null || index.chunks == 0) {
            return fromGemini(question);
        }

        List<Document> matches = index.store.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(TOP_K)
                .similarityThreshold(0.0)
                .build());

        if (matches == null || matches.isEmpty()) {
            return fromGemini(question);
        }

        double bestScore = matches.stream().mapToDouble(this::extractSimilarityScore).max().orElse(0.0d);
        if (bestScore < properties.docBuddySimilarityThreshold()) {
            log.info("Doc Buddy fallback: low similarity score {} < threshold {}", bestScore, properties.docBuddySimilarityThreshold());
            return fromGemini(question);
        }

        String localAnswer = answerFromLocalDocs(question, matches);
        if (localAnswer.contains(INSUFFICIENT_CONTEXT_TOKEN)) {
            log.info("Doc Buddy fallback: local model reported insufficient context");
            return fromGemini(question);
        }

        String answer = "Based on your local documents...\n\n" + localAnswer.trim();
        return new DocBuddyResponse(answer, "Documents");
    }

    private String answerFromLocalDocs(String question, List<Document> matches) {
        String context = matches.stream()
                .map(doc -> "FILE: " + String.valueOf(doc.getMetadata().getOrDefault("filename", "unknown")) + "\n" + doc.getText())
                .reduce((a, b) -> a + "\n\n---\n\n" + b)
                .orElse("");

        String prompt = """
                You are Doc Buddy. Use ONLY the provided context to answer the question.
                If the context does not contain enough information, reply with exactly: %s

                Output format:
                - Start with a Markdown heading
                - Use bullet points for key details
                - Keep answer concise and factual

                Question:
                %s

                Context:
                %s
                """.formatted(INSUFFICIENT_CONTEXT_TOKEN, question, context);

        return ChatClient.builder(chatModel)
                .build()
                .prompt()
                .user(prompt)
                .call()
                .content();
    }

    private DocBuddyResponse fromGemini(String question) {
        String answer = geminiFallbackService.answerFromGeneralKnowledge(question);
        String labeled = "I couldn't find this in your documents, but here is what I found via Gemini AI...\n\n" + answer.trim();
        return new DocBuddyResponse(labeled, "Gemini AI");
    }

    private synchronized CachedIndex getOrBuildIndex() throws IOException {
        Path dir = properties.documentsDir().toAbsolutePath().normalize();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        List<Path> files = listSupportedFiles(dir);
        long signature = computeSignature(files);

        CachedIndex current = this.cachedIndex;
        if (current != null && current.signature == signature) {
            return current;
        }

        List<ParsedUpload> uploads = new ArrayList<>();
        for (Path path : files) {
            try {
                String text = documentParsingService.extractText(path);
                uploads.add(new ParsedUpload(path.getFileName().toString(), text));
            } catch (Exception e) {
                log.warn("Skipping unreadable document: {}", path.getFileName());
            }
        }

        VectorStore store = null;
        int chunks = 0;
        if (!uploads.isEmpty()) {
            store = ragIngestionService.buildIndex(uploads);
            List<Document> probe = store.similaritySearch(SearchRequest.builder()
                    .query("overview")
                    .topK(500)
                    .similarityThreshold(0.0)
                    .build());
            chunks = probe != null ? probe.size() : 0;
        }

        CachedIndex rebuilt = new CachedIndex(store, signature, chunks, Instant.now());
        this.cachedIndex = rebuilt;
        log.info("Doc Buddy index refreshed with {} file(s), {} chunks", uploads.size(), chunks);
        return rebuilt;
    }

    private List<Path> listSupportedFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isAllowed(path.getFileName() != null ? path.getFileName().toString() : ""))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private boolean isAllowed(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return ALLOWED_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    private long computeSignature(List<Path> files) {
        long signature = 1;
        for (Path p : files) {
            try {
                signature = 31 * signature + p.toAbsolutePath().toString().hashCode();
                signature = 31 * signature + Files.size(p);
                signature = 31 * signature + Files.getLastModifiedTime(p).toMillis();
            } catch (IOException ignored) {
                signature = 31 * signature + p.toAbsolutePath().toString().hashCode();
            }
        }
        return signature;
    }

    private double extractSimilarityScore(Document doc) {
        Object score = doc.getMetadata().get("score");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        Object distance = doc.getMetadata().get("distance");
        if (distance instanceof Number number) {
            return 1.0d - number.doubleValue();
        }
        try {
            Object reflected = doc.getClass().getMethod("getScore").invoke(doc);
            if (reflected instanceof Number number) {
                return number.doubleValue();
            }
        } catch (Exception ignored) {
            // no-op
        }
        return 0.0d;
    }

    private record CachedIndex(VectorStore store, long signature, int chunks, Instant builtAt) {
    }

    public record DocBuddyResponse(String answer, String source) {
    }
}
