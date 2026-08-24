package com.letsblog.content.controller;

import com.letsblog.content.dto.CustomTagRequest;
import com.letsblog.content.dto.CustomTagResponse;
import com.letsblog.content.dto.GenerateCustomTagRequest;
import com.letsblog.content.dto.GenerateCustomTagResponse;
import com.letsblog.content.dto.ValidateCustomTagRequest;
import com.letsblog.content.dto.ValidationResult;
import com.letsblog.content.service.CustomTagService;
import com.letsblog.content.service.CustomTagGenerationService;
import com.letsblog.content.service.CustomTagValidationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

import java.util.List;

@RestController
@RequestMapping("/api/custom-tags")
public class CustomTagController {

    private final CustomTagService customTagService;
    private final CustomTagGenerationService customTagGenerationService;
    private final CustomTagValidationService customTagValidationService;

    public CustomTagController(
            CustomTagService customTagService,
            CustomTagGenerationService customTagGenerationService,
            CustomTagValidationService customTagValidationService) {
        this.customTagService = customTagService;
        this.customTagGenerationService = customTagGenerationService;
        this.customTagValidationService = customTagValidationService;
    }

    @PostMapping("/generate")
    public ResponseEntity<GenerateCustomTagResponse> generate(@Valid @RequestBody GenerateCustomTagRequest request) {
        GenerateCustomTagResponse response = customTagGenerationService.generate(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/validate")
    public ResponseEntity<ValidationResult> validate(@Valid @RequestBody ValidateCustomTagRequest request) {
        ValidationResult result = customTagValidationService.validate(request.htmlTemplate(), request.cssContent());
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<CustomTagResponse> create(@Valid @RequestBody CustomTagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customTagService.create(request));
    }

    @GetMapping
    public List<CustomTagResponse> list(@RequestParam(required = false) Long projectId) {
        return customTagService.list(projectId);
    }

    @GetMapping("/css-bundle")
    public ResponseEntity<byte[]> cssBundle(@RequestParam(required = false) Long projectId) {
        byte[] css = customTagService.buildCssBundle(projectId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/css"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"custom-tags.css\"")
                .body(css);
    }

    @PutMapping("/{id}")
    public CustomTagResponse update(@PathVariable Long id, @Valid @RequestBody CustomTagRequest request) {
        return customTagService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        customTagService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
