package com.letsblog.project.controller;

import com.letsblog.project.dto.FacebookPagesView;
import com.letsblog.project.dto.FacebookSelectPageRequest;
import com.letsblog.project.dto.XAuthorizeRequest;
import com.letsblog.project.dto.XAuthorizeResponse;
import com.letsblog.project.dto.XCallbackRequest;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.SnsFacebookService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト設定画面の「Facebook」欄の API(issue #1580)。OAuth のトークンは本番サイトのプラグインへ
 * 送るだけで、どの応答にも載らない。認可のあとに投稿先のページを選ぶ({@code /pages}、{@code /page})点が X・Threads と違う。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/sns/facebook")
public class ProjectSnsFacebookController {

    private final SnsFacebookService snsFacebookService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectSnsFacebookController(
            SnsFacebookService snsFacebookService, AdminAuthorizationService adminAuthorizationService) {
        this.snsFacebookService = snsFacebookService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /** 接続状態・接続できない理由・告知履歴。本番サイトに届かないときは「取得できない」として返す。 */
    @GetMapping
    public XConnectionView view(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return snsFacebookService.view(projectId);
    }

    /** 認可の開始。Facebook の認可画面の URL を返す。 */
    @PostMapping("/authorize")
    public XAuthorizeResponse authorize(@PathVariable Long projectId, @Valid @RequestBody XAuthorizeRequest request) {
        adminAuthorizationService.requireAdmin();
        return new XAuthorizeResponse(snsFacebookService.startAuthorization(
                projectId, request.clientId(), request.clientSecret(), request.redirectUri()));
    }

    /** 認可画面から戻ったときの state と認可コード。選べるページの一覧(ID と名前)だけを返す。 */
    @PostMapping("/callback")
    public FacebookPagesView callback(@PathVariable Long projectId, @Valid @RequestBody XCallbackRequest request) {
        adminAuthorizationService.requireAdmin();
        return snsFacebookService.completeAuthorization(projectId, request.state(), request.code());
    }

    /** 認可のあと、投稿先に選べるページの一覧。 */
    @GetMapping("/pages")
    public FacebookPagesView pages(@PathVariable Long projectId, @RequestParam String state) {
        adminAuthorizationService.requireAdmin();
        return snsFacebookService.pages(projectId, state);
    }

    /** 投稿先のページを選ぶ。そのページのトークンだけを本番サイトへ送り、ページ名だけを返す。 */
    @PostMapping("/page")
    public XConnectResult selectPage(@PathVariable Long projectId, @Valid @RequestBody FacebookSelectPageRequest request) {
        adminAuthorizationService.requireAdmin();
        return snsFacebookService.selectPage(projectId, request.state(), request.pageId());
    }

    /** テスト投稿。 */
    @PostMapping("/test")
    public XTestResult test(@PathVariable Long projectId) {
        adminAuthorizationService.requireAdmin();
        return snsFacebookService.test(projectId);
    }

    /** 切断。本番サイトのプラグインの Facebook の設定を消す。 */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable Long projectId) {
        adminAuthorizationService.requireAdmin();
        snsFacebookService.disconnect(projectId);
    }
}
