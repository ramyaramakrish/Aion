package com.aion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class DocBuddyService {

    private static final Logger log = LoggerFactory.getLogger(DocBuddyService.class);
    private final GeminiFallbackService geminiFallbackService;

    public DocBuddyService(
            GeminiFallbackService geminiFallbackService) {
        this.geminiFallbackService = geminiFallbackService;
    }

    public DocBuddyResponse ask(String question) throws IOException {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question cannot be empty");
        }
        log.info("Forwarding Doc Buddy question to Gemini AI.");
        return fromGemini(question);
    }

    private DocBuddyResponse fromGemini(String question) {
        String answer = geminiFallbackService.answerFromGeneralKnowledge(question).trim();
        String labeled = "Answering via Gemini AI...\n\n" + answer;
        return new DocBuddyResponse(labeled, "Gemini AI");
    }

    public record DocBuddyResponse(String answer, String source) {
    }
}
