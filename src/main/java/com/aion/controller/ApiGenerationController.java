package com.aion.controller;

import com.aion.service.ApiGenerationService;
import com.aion.service.ApiGenerationService.GenerationResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
public class ApiGenerationController {

    private final ApiGenerationService apiGenerationService;

    public ApiGenerationController(ApiGenerationService apiGenerationService) {
        this.apiGenerationService = apiGenerationService;
    }

    @PostMapping(value = "/api/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> generate(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "fastMode", defaultValue = "true") boolean fastMode) {
        try {
            GenerationResult result = apiGenerationService.generate(files, fastMode);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "error", e.getMessage() != null ? e.getMessage() : "Generation failed"));
        }
    }
}
