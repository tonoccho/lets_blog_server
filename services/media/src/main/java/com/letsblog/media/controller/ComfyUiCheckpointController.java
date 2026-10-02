package com.letsblog.media.controller;

import com.letsblog.media.dto.DeleteComfyUiCheckpointCommand;
import com.letsblog.media.dto.InstallComfyUiCheckpointCommand;
import com.letsblog.media.service.ModelInstallJobRunner;
import jakarta.validation.Valid;
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
 * Bearerトークンをそのまま転送してもらうが、ここで検証するのみで、以前のように非同期実行の間
 * (ModelInstallJobRunner)引き回すことはしない。ダウンロードがKeycloakの
 * {@code accessTokenLifespan}(既定300秒)を超えて長引くとこのトークンが失効し、
 * 完了/失敗通知が握りつぶされてジョブがDB上{@code running}のまま残る不具合があったため
 * (issue #1083)、GenerationJob更新呼び出しはmedia-service自身のClient Credentialsトークンで
 * 認証するように変更した({@link com.letsblog.common.client.GenerationJobClient}参照)。
 */
@RestController
public class ComfyUiCheckpointController {

    private final ModelInstallJobRunner modelInstallJobRunner;

    public ComfyUiCheckpointController(ModelInstallJobRunner modelInstallJobRunner) {
        this.modelInstallJobRunner = modelInstallJobRunner;
    }

    /**
     * 認可不要: gatewayのルート表に載っておらず外部から到達できない(issue #830 で
     * RouteControllerContractTest の NON_GATEWAY_ROUTED_PATHS として明示済み)。
     * legacy-apiのMediaComfyUiClientがdocker network越しに直接呼ぶ経路しか無く、
     * admin判定は呼び出し元(legacy-apiのComfyUiModelService)が済ませている。
     */
    @PostMapping("/api/comfyui/checkpoints/install")
    public ResponseEntity<Void> install(@Valid @RequestBody InstallComfyUiCheckpointCommand command) {
        modelInstallJobRunner.runComfyUiDownload(command.jobId(), command.downloadUrl(), command.fileName());
        return ResponseEntity.accepted().build();
    }

    /** 認可不要: {@link #install}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #830)。 */
    @PostMapping("/api/comfyui/checkpoints/delete")
    public ResponseEntity<Void> delete(@Valid @RequestBody DeleteComfyUiCheckpointCommand command) {
        modelInstallJobRunner.runComfyUiDelete(command.jobId(), command.fileName());
        return ResponseEntity.accepted().build();
    }
}
