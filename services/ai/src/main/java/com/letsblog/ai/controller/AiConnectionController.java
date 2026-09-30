package com.letsblog.ai.controller;

import com.letsblog.ai.dto.AiConnectionResponse;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.AiConnectionService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** プロジェクトから見たAIプロバイダー4種の接続情報と利用可否を返すAPI(issue #1499)。 */
@RestController
@RequestMapping("/api/projects/{id}/ai-connections")
public class AiConnectionController {

    private final AiConnectionService aiConnectionService;
    private final AdminAuthorizationService adminAuthorizationService;

    public AiConnectionController(
            AiConnectionService aiConnectionService, AdminAuthorizationService adminAuthorizationService) {
        this.aiConnectionService = aiConnectionService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping
    public List<AiConnectionResponse> listAiConnections(@PathVariable Long id) {
        adminAuthorizationService.requireProjectMemberOrAdmin(id);
        return aiConnectionService.listConnections(id);
    }
}
