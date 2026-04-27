package com.aion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DocBuddyService {

    private static final Logger log = LoggerFactory.getLogger(DocBuddyService.class);
    
    // Existing fallback service
    private final GeminiFallbackService geminiFallbackService;
    
    // New Spring AI components
    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public DocBuddyService(
            GeminiFallbackService geminiFallbackService,
            ChatClient.Builder chatClientBuilder, 
            EmbeddingModel embeddingModel) {
        this.geminiFallbackService = geminiFallbackService;
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = SimpleVectorStore.builder(embeddingModel).build();
    }

   @PostConstruct
    public void initLocalDocuments() {
        Path docsPath = Paths.get("C:/Github/Documents");
        
        if (!Files.exists(docsPath) || !Files.isDirectory(docsPath)) {
            log.warn("Local documents directory not found: {}", docsPath);
            return;
        }

        try (Stream<Path> paths = Files.list(docsPath)) {
            paths.filter(Files::isRegularFile)
                 // Ignore temporary Microsoft Office lock files
                 .filter(path -> !path.getFileName().toString().startsWith("~$"))
                 .forEach(file -> {
                try {
                    TikaDocumentReader reader = new TikaDocumentReader(file.toUri().toString());
                    List<Document> documents = reader.get();

                    TokenTextSplitter splitter = new TokenTextSplitter();
                    List<Document> splitDocs = splitter.apply(documents);

                    vectorStore.accept(splitDocs);
                    log.info("Successfully ingested: {}", file.getFileName());
                    
                    // Add a 2-second delay to prevent hitting the Gemini Free Tier Rate Limits
                    Thread.sleep(2000); 
                    
                } catch (Exception e) {
                    log.error("Failed to read file: {} - {}", file.getFileName(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Error scanning directory: {}", e.getMessage());
        }
    }


    public DocBuddyResponse ask(String question) throws IOException {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question cannot be empty");
        }

        // 1. Search the local vector store first
        SearchRequest searchRequest = SearchRequest.builder()
        .query(question)
        .topK(3)
        .similarityThreshold(0.25)
        .build();

        List<Document> similarDocs = vectorStore.similaritySearch(searchRequest);

        // 2. Conditional Routing
        if (similarDocs != null && !similarDocs.isEmpty()) {
            log.info("Found relevant local documents. Using RAG.");
            return fromLocalDocuments(question, similarDocs);
        }

        // 3. Fallback to existing logic
        log.info("No relevant local documents found. Forwarding to Gemini AI.");
        return fromGemini(question);
    }

    private DocBuddyResponse fromLocalDocuments(String question, List<Document> similarDocs) {
        String context = similarDocs.stream()
        .map(Document::getText)
        .collect(Collectors.joining("\n\n"));

        String answer = chatClient.prompt()
                .system(s -> s.text("""
                        You are a helpful document assistant. Answer the user's question using ONLY the provided local context.
                        If the context doesn't fully answer the question, state what you found and rely on your general knowledge for the rest.
                        
                        CONTEXT:
                        {context}
                        """).param("context", context))
                .user(question)
                .call()
                .content();

        return new DocBuddyResponse(answer, "Local Documents");
    }

    // Unmodified existing fallback method
    private DocBuddyResponse fromGemini(String question) {
        String answer = geminiFallbackService.answerFromGeneralKnowledge(question).trim();
        String labeled = "Answering via Gemini AI...\n\n" + answer;
        return new DocBuddyResponse(labeled, "Gemini AI");
    }

    public record DocBuddyResponse(String answer, String source) {
    }
}