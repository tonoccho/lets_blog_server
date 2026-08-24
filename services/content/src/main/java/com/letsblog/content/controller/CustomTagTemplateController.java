package com.letsblog.content.controller;

import com.letsblog.content.dto.CloneCustomTagTemplateRequest;
import com.letsblog.content.dto.CustomTagTemplateRequest;
import com.letsblog.content.dto.CustomTagTemplateResponse;
import com.letsblog.content.service.CustomTagTemplateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/custom-tag-templates")
public class CustomTagTemplateController {

    private final CustomTagTemplateService customTagTemplateService;

    public CustomTagTemplateController(CustomTagTemplateService customTagTemplateService) {
        this.customTagTemplateService = customTagTemplateService;
    }

    @PostMapping
    public ResponseEntity<CustomTagTemplateResponse> create(@Valid @RequestBody CustomTagTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customTagTemplateService.create(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomTagTemplateResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(customTagTemplateService.getById(id));
    }

    @GetMapping
    public ResponseEntity<List<CustomTagTemplateResponse>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean showAll) {

        if (search != null && !search.isBlank()) {
            return ResponseEntity.ok(customTagTemplateService.searchByKeyword(search, projectId));
        }

        if (category != null && !category.isBlank()) {
            return ResponseEntity.ok(customTagTemplateService.filterByCategory(category, projectId));
        }

        if (showAll) {
            return ResponseEntity.ok(customTagTemplateService.list(projectId));
        }

        return ResponseEntity.ok(customTagTemplateService.listPublished(projectId));
    }

    @GetMapping("/my-templates")
    public ResponseEntity<List<CustomTagTemplateResponse>> getMyTemplates() {
        return ResponseEntity.ok(customTagTemplateService.getMyTemplates());
    }

    @PutMapping("/{id}")
    public ResponseEntity<CustomTagTemplateResponse> update(@PathVariable Long id, @Valid @RequestBody CustomTagTemplateRequest request) {
        return ResponseEntity.ok(customTagTemplateService.update(id, request));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<CustomTagTemplateResponse> publish(@PathVariable Long id) {
        return ResponseEntity.ok(customTagTemplateService.publish(id));
    }

    @PostMapping("/{id}/unpublish")
    public ResponseEntity<CustomTagTemplateResponse> unpublish(@PathVariable Long id) {
        return ResponseEntity.ok(customTagTemplateService.unpublish(id));
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<CustomTagTemplateResponse> clone(@PathVariable Long id, @Valid @RequestBody CloneCustomTagTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customTagTemplateService.clone(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        customTagTemplateService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
