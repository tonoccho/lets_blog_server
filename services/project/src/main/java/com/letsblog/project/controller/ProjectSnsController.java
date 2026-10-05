package com.letsblog.project.controller;

import com.letsblog.project.dto.XAuthorizeRequest;
import com.letsblog.project.dto.XAuthorizeResponse;
import com.letsblog.project.dto.XCallbackRequest;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.SnsXService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト設定画面の「SNS 告知」欄(X)の API(issue #1574)。OAuth のトークンは本番サイトのプラグインへ
 * 送るだけで、どの応答にも載らない。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/sns/x")
public class ProjectSnsController {

    private final SnsXService snsXService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectSnsController(SnsXService snsXService, AdminAuthorizationService adminAuthorizationService) {
        this.snsXService = snsXService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /** 接続状態・接続できない理由・告知履歴。本番サイトに届かないときは「取得できない」として返す。 */
    @GetMapping
    public XConnectionView view(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return snsXService.view(projectId);
    }

    /** 認可の開始。X の認可画面の URL を返す。 */
    @PostMapping("/authorize")
    public XAuthorizeResponse authorize(@PathVariable Long projectId, @Valid @RequestBody XAuthorizeRequest request) {
        adminAuthorizationService.requireAdmin();
        return new XAuthorizeResponse(snsXService.startAuthorization(
                projectId, request.clientId(), request.clientSecret(), request.redirectUri()));
    }

    /** X の認可画面から戻ったときの state と認可コード。トークンは本番サイトへ送り、アカウント名だけを返す。 */
    @PostMapping("/callback")
    public XConnectResult callback(@PathVariable Long projectId, @Valid @RequestBody XCallbackRequest request) {
        adminAuthorizationService.requireAdmin();
        return snsXService.completeAuthorization(projectId, request.state(), request.code());
    }

    /** テスト投稿。 */
    @PostMapping("/test")
    public XTestResult test(@PathVariable Long projectId) {
        adminAuthorizationService.requireAdmin();
        return snsXService.test(projectId);
    }
}
