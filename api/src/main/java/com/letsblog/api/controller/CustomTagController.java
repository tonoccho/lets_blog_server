package com.letsblog.api.controller;

import com.letsblog.api.dto.CustomTagRequest;
import com.letsblog.api.dto.CustomTagResponse;
import com.letsblog.api.service.CustomTagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/custom-tags")
public class CustomTagController {

    private final CustomTagService customTagService;

    public CustomTagController(CustomTagService customTagService) {
        this.customTagService = customTagService;
    }

    @PostMapping
    public ResponseEntity<CustomTagResponse> create(@Valid @RequestBody CustomTagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customTagService.create(request));
    }

    @GetMapping
    public List<CustomTagResponse> list() {
        return customTagService.list();
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
