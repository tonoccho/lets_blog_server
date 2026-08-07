package com.letsblog.api.controller;

import com.letsblog.api.dto.CustomTagRequest;
import com.letsblog.api.dto.CustomTagResponse;
import com.letsblog.api.dto.GenerateCustomTagRequest;
import com.letsblog.api.dto.GenerateCustomTagResponse;
import com.letsblog.api.service.CustomTagService;
import com.letsblog.api.service.CustomTagGenerationService;
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

    public CustomTagController(
            CustomTagService customTagService,
            CustomTagGenerationService customTagGenerationService) {
        this.customTagService = customTagService;
        this.customTagGenerationService = customTagGenerationService;
    }

    @PostMapping("/generate")
    public ResponseEntity<GenerateCustomTagResponse> generate(@Valid @RequestBody GenerateCustomTagRequest request) {
        GenerateCustomTagResponse response = customTagGenerationService.generate(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
