package com.letsblog.ai.controller;

import com.letsblog.ai.dto.ProjectConnectionsResponse;
import com.letsblog.ai.dto.UpdateProjectConnectionsRequest;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.ProjectConnectionService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト単位のOllama / ComfyUI接続先の上書きを参照・更新するAPI(issue #1503)。
 * プロジェクトメンバー(またはadmin)のみ。ComfyUIを呼ぶのはmedia-serviceだが、値の所有者は
 * ai-service(project_ai_settings、ADR-0004)なので、パスはai-modelsのまま本サービスが受ける
 * (gatewayで{@code /api/projects/*}{@code /ai-models/**}のmedia向けルートより先にマッチさせる)。
 */
@RestController
@RequestMapping("/api/projects/{id}/ai-models/connections")
public class ProjectConnectionController {

    private final ProjectConnectionService projectConnectionService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectConnectionController(
            ProjectConnectionService projectConnectionService, AdminAuthorizationService adminAuthorizationService) {
        this.projectConnectionService = projectConnectionService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping
    @Operation(operationId = "getProjectConnections")
    public ProjectConnectionsResponse get(@PathVariable Long id) {
        adminAuthorizationService.requireProjectMemberOrAdmin(id);
        return projectConnectionService.get(id);
    }

    /** 項目を省略すると変更せず、空文字はその項目の上書きを解除する。不正なURLは400で何も保存しない。 */
    @PutMapping
    @Operation(operationId = "putProjectConnections")
    public ProjectConnectionsResponse put(@PathVariable Long id, @RequestBody UpdateProjectConnectionsRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(id);
        return projectConnectionService.update(id, request);
    }
}
