package com.letsblog.project.controller;

import com.letsblog.project.dto.XAuthorizeRequest;
import com.letsblog.project.dto.XAuthorizeResponse;
import com.letsblog.project.dto.XCallbackRequest;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.SnsThreadsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト設定画面の「Threads」欄の API(issue #1579)。OAuth のトークンは本番サイトのプラグインへ
 * 送るだけで、どの応答にも載らない。リクエスト・応答の形は X({@link ProjectSnsController})と同じ。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/sns/threads")
public class ProjectSnsThreadsController {

    private final SnsThreadsService snsThreadsService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectSnsThreadsController(
            SnsThreadsService snsThreadsService, AdminAuthorizationService adminAuthorizationService) {
        this.snsThreadsService = snsThreadsService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /** 接続状態・接続できない理由・告知履歴。本番サイトに届かないときは「取得できない」として返す。 */
    @GetMapping
    public XConnectionView view(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return snsThreadsService.view(projectId);
    }

    /** 認可の開始。Threads の認可画面の URL を返す。 */
    @PostMapping("/authorize")
    public XAuthorizeResponse authorize(@PathVariable Long projectId, @Valid @RequestBody XAuthorizeRequest request) {
        adminAuthorizationService.requireAdmin();
        return new XAuthorizeResponse(snsThreadsService.startAuthorization(
                projectId, request.clientId(), request.clientSecret(), request.redirectUri()));
    }

    /** Threads の認可画面から戻ったときの state と認可コード。トークンは本番サイトへ送り、アカウント名だけを返す。 */
    @PostMapping("/callback")
    public XConnectResult callback(@PathVariable Long projectId, @Valid @RequestBody XCallbackRequest request) {
        adminAuthorizationService.requireAdmin();
        return snsThreadsService.completeAuthorization(projectId, request.state(), request.code());
    }

    /** テスト投稿。 */
    @PostMapping("/test")
    public XTestResult test(@PathVariable Long projectId) {
        adminAuthorizationService.requireAdmin();
        return snsThreadsService.test(projectId);
    }

    /** 切断。本番サイトのプラグインの Threads の設定を消す。 */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable Long projectId) {
        adminAuthorizationService.requireAdmin();
        snsThreadsService.disconnect(projectId);
    }
}
