package com.letsblog.media.controller;

import com.letsblog.media.dto.DeleteComfyUiCheckpointCommand;
import com.letsblog.media.dto.InstallComfyUiCheckpointCommand;
import com.letsblog.media.service.ModelInstallJobRunner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-apiの{@code ComfyUiModelService}から、チェックポイントのダウンロード/削除の起動
 * (トリガー)のみを受け取る内部向けエンドポイント(issue #573 stage2)。実行自体は
 * {@link ModelInstallJobRunner}が非同期に行い、進捗・完了はlegacy-apiが所有する
 * GenerationJob(jobId、リクエストに含まれる)へ{@code PATCH /api/generation-jobs/{id}}
 * (GenerationJobClient経由)で反映する。
 *
 * <p>gatewayは経由しない(legacy-apiコンテナからmediaコンテナへdocker network越しに直接呼ぶ、
 * MediaRenderClientの逆方向と同じ経路)。認証は、legacy-api側で既に検証済みのユーザーの
 * Bearerトークンをそのまま転送してもらい、ここではさらに検証はせず、非同期実行の間
 * (ModelInstallJobRunner)引き回してGenerationJob更新呼び出しに使う。
 */
@RestController
public class ComfyUiCheckpointController {

    private final ModelInstallJobRunner modelInstallJobRunner;
    private final HttpServletRequest request;

    public ComfyUiCheckpointController(ModelInstallJobRunner modelInstallJobRunner, HttpServletRequest request) {
        this.modelInstallJobRunner = modelInstallJobRunner;
        this.request = request;
    }

    @PostMapping("/api/comfyui/checkpoints/install")
    public ResponseEntity<Void> install(@Valid @RequestBody InstallComfyUiCheckpointCommand command) {
        modelInstallJobRunner.runComfyUiDownload(
                command.jobId(), command.downloadUrl(), command.fileName(), bearerToken());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/comfyui/checkpoints/delete")
    public ResponseEntity<Void> delete(@Valid @RequestBody DeleteComfyUiCheckpointCommand command) {
        modelInstallJobRunner.runComfyUiDelete(command.jobId(), command.fileName(), bearerToken());
        return ResponseEntity.accepted().build();
    }

    private String bearerToken() {
        return request.getHeader(HttpHeaders.AUTHORIZATION);
    }
}
