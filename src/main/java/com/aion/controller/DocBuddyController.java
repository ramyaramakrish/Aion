package com.aion.controller;

import com.aion.service.DocBuddyService;
import com.aion.service.DocBuddyService.DocBuddyResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Validated
public class DocBuddyController {

    private final DocBuddyService docBuddyService;

    public DocBuddyController(DocBuddyService docBuddyService) {
        this.docBuddyService = docBuddyService;
    }

    @PostMapping("/api/doc-buddy/ask")
    public ResponseEntity<?> ask(@Valid @RequestBody AskRequest request) {
        try {
            DocBuddyResponse response = docBuddyService.ask(request.question());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "error", e.getMessage() != null ? e.getMessage() : "Doc Buddy failed"));
        }
    }

    public record AskRequest(@NotBlank String question) {
    }
}
