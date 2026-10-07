package com.letsblog.project.controller;

import com.letsblog.project.dto.XAuthorizeRequest;
import com.letsblog.project.dto.XAuthorizeResponse;
import com.letsblog.project.dto.HatenaCallbackRequest;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.SnsHatenaService;
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
 * プロジェクト設定画面の「はてなブックマーク」欄の API(issue #1582)。OAuth(1.0a)のトークンとその秘密は本番サイトのプラグインへ
 * 送るだけで、どの応答にも載らない。リクエスト・応答の形は X({@link ProjectSnsController})と同じ。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/sns/hatena")
public class ProjectSnsHatenaController {

    private final SnsHatenaService snsHatenaService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectSnsHatenaController(
            SnsHatenaService snsHatenaService, AdminAuthorizationService adminAuthorizationService) {
        this.snsHatenaService = snsHatenaService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /** 接続状態・接続できない理由・告知履歴。本番サイトに届かないときは「取得できない」として返す。 */
    @GetMapping
    public XConnectionView view(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return snsHatenaService.view(projectId);
    }

    /** 認可の開始。はてなブックマーク の認可画面の URL を返す。 */
    @PostMapping("/authorize")
    public XAuthorizeResponse authorize(@PathVariable Long projectId, @Valid @RequestBody XAuthorizeRequest request) {
        adminAuthorizationService.requireAdmin();
        return new XAuthorizeResponse(snsHatenaService.startAuthorization(
                projectId, request.clientId(), request.clientSecret(), request.redirectUri()));
    }

    /** はてなの認可画面から戻ったときの state・リクエストトークン・verifier。トークンは本番サイトへ送り、アカウント名だけを返す。 */
    @PostMapping("/callback")
    public XConnectResult callback(@PathVariable Long projectId, @Valid @RequestBody HatenaCallbackRequest request) {
        adminAuthorizationService.requireAdmin();
        return snsHatenaService.completeAuthorization(
                projectId, request.state(), request.oauthToken(), request.oauthVerifier());
    }

    /** テスト投稿。 */
    @PostMapping("/test")
    public XTestResult test(@PathVariable Long projectId) {
        adminAuthorizationService.requireAdmin();
        return snsHatenaService.test(projectId);
    }

    /** 切断。本番サイトのプラグインの はてなブックマーク の設定を消す。 */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable Long projectId) {
        adminAuthorizationService.requireAdmin();
        snsHatenaService.disconnect(projectId);
    }
}
