package com.letsblog.api.controller;

import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.dto.GenerationJobResponse;
import com.letsblog.api.repository.GenerationJobRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * Ollama/ComfyUI呼び出しジョブの履歴一覧(Web管理フロントエンドの表示用)。
 * ジョブの作成自体は06-ollama-integration / 07-comfyui-integration 側で行う。
 */
@RestController
@RequestMapping("/api/generation-jobs")
public class GenerationJobController {

    private final GenerationJobRepository generationJobRepository;

    public GenerationJobController(GenerationJobRepository generationJobRepository) {
        this.generationJobRepository = generationJobRepository;
    }

    @GetMapping
    public List<GenerationJobResponse> list() {
        return generationJobRepository.findAll().stream()
                .sorted(Comparator.comparing(GenerationJob::getCreatedAt).reversed())
                .map(job -> new GenerationJobResponse(
                        job.getId(), job.getType(), job.getStatus(), job.getCreatedAt(), job.getUpdatedAt()))
                .toList();
    }
}
