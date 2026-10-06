package com.letsblog.content.controller;

import com.letsblog.content.dto.CustomTagRequest;
import com.letsblog.content.dto.CustomTagResponse;
import com.letsblog.content.dto.GenerateCustomTagRequest;
import com.letsblog.content.dto.GenerateCustomTagResponse;
import com.letsblog.content.dto.GenerationJobResponse;
import com.letsblog.content.dto.ValidateCustomTagRequest;
import com.letsblog.content.dto.ValidationResult;
import com.letsblog.content.service.CustomTagService;
import com.letsblog.content.service.CustomTagGenerationJobStarter;
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
    private final CustomTagGenerationJobStarter customTagGenerationJobStarter;

    public CustomTagController(
            CustomTagService customTagService,
            CustomTagGenerationService customTagGenerationService,
            CustomTagValidationService customTagValidationService,
            CustomTagGenerationJobStarter customTagGenerationJobStarter) {
        this.customTagService = customTagService;
        this.customTagGenerationService = customTagGenerationService;
        this.customTagValidationService = customTagValidationService;
        this.customTagGenerationJobStarter = customTagGenerationJobStarter;
    }

    @PostMapping("/generate")
    public ResponseEntity<GenerateCustomTagResponse> generate(@Valid @RequestBody GenerateCustomTagRequest request) {
        GenerateCustomTagResponse response = customTagGenerationService.generate(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * カスタムタグのAI生成を非同期ジョブとして受理する(issue #1409)。生成の完了を待たずにジョブIDを返し、
     * 状態と結果(生成したHTML/CSS)は{@code GET /api/generation-jobs/{id}}で引く。生成結果は
     * {@code custom_tags}へ書かれず、利用者が確認して{@code POST /api/custom-tags}で「保存」する。
     * 同期の{@link #generate}(生成と同時に保存する)は変えない。認可は同じ(admin限定、受理側で確認)。
     *
     * <p>パスを分けたのは画像生成の{@code POST /api/ai/image/jobs}(#1405)に揃えるため。
     */
    @PostMapping("/generate/jobs")
    public ResponseEntity<GenerationJobResponse> generateJob(
            @Valid @RequestBody GenerateCustomTagRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.accepted()
                .body(GenerationJobResponse.from(customTagGenerationJobStarter.start(request, authorization)));
    }

    /**
     * 認可不要: 渡されたHTML/CSSの記法を検査して結果を返すだけの純粋な関数で、
     * 保存済みリソースを読み書きしない(issue #830)。エディタの入力補助として使われる。
     */
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
