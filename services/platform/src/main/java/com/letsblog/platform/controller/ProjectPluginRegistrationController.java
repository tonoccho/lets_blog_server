package com.letsblog.platform.controller;

import com.letsblog.platform.service.ProjectPluginRegistrationService;
import com.letsblog.platform.service.ProjectPluginRegistrationService.ProjectRegistrationView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * プロジェクト単位のプラグインの登録の上書きのAPI(issue #1808)。gatewayの{@code /api/projects/*}{@code /plugin-registrations/**}を経由する。
 * 認可(プロジェクトのメンバーまたは管理者)はサービス層のrequireProjectMemberOrAdminが担う。
 * 認証情報は書き込みだけで、取得は{@code credentialConfigured}(設定済みか)しか返さない。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/plugin-registrations")
public class ProjectPluginRegistrationController {

    public record CreateRequest(String kind, String name, String url, String drawioUrl, String credential) {
    }

    /** {@code credential}が空または未指定なら保存済みの認証情報を変えない。 */
    public record UpdateRequest(String name, String url, String drawioUrl, String credential) {
    }

    private final ProjectPluginRegistrationService projectPluginRegistrationService;

    public ProjectPluginRegistrationController(ProjectPluginRegistrationService service) {
        this.projectPluginRegistrationService = service;
    }

    @GetMapping
    public List<ProjectRegistrationView> list(@PathVariable Long projectId) {
        return projectPluginRegistrationService.list(projectId);
    }

    @PostMapping
    public ProjectRegistrationView create(@PathVariable Long projectId, @RequestBody CreateRequest request) {
        return projectPluginRegistrationService.create(
                projectId, request.kind(), request.name(), request.url(), request.drawioUrl(), request.credential());
    }

    @PutMapping("/{id}")
    public ProjectRegistrationView update(
            @PathVariable Long projectId, @PathVariable Long id, @RequestBody UpdateRequest request) {
        return projectPluginRegistrationService.update(projectId, id, request.name(), request.url(), request.drawioUrl(), request.credential());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long projectId, @PathVariable Long id) {
        projectPluginRegistrationService.delete(projectId, id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/in-use")
    public ProjectRegistrationView activate(@PathVariable Long projectId, @PathVariable Long id) {
        return projectPluginRegistrationService.activate(projectId, id);
    }

    @DeleteMapping("/{id}/credential")
    public ProjectRegistrationView clearCredential(@PathVariable Long projectId, @PathVariable Long id) {
        return projectPluginRegistrationService.clearCredential(projectId, id);
    }
}
