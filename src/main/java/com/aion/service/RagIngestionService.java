package com.aion.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RagIngestionService {

    private final EmbeddingModel embeddingModel;

    public RagIngestionService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Builds an in-memory vector index from uploaded document texts (chunked for RAG).
     */
    public VectorStore buildIndex(List<ParsedUpload> uploads) {
        List<Document> sourceDocs = uploads.stream().map(u -> {
            Map<String, Object> meta = new HashMap<>();
            meta.put("filename", u.filename());
            meta.put("kind", "requirement");
            return new Document(u.text(), meta);
        }).toList();

        TokenTextSplitter splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(sourceDocs);

        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        store.add(chunks);
        return store;
    }

    public record ParsedUpload(String filename, String text) {}
}
