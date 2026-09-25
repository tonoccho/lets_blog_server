package com.letsblog.platform.controller;

import com.letsblog.platform.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse;
import com.letsblog.platform.dto.ContainerStatusResponse;
import com.letsblog.platform.service.AdminAuthorizationService;
import com.letsblog.platform.service.ConnectedServiceStatusBroadcaster;
import com.letsblog.platform.service.ConnectedServiceStatusService;
import com.letsblog.platform.service.ContainerStatusBroadcaster;
import com.letsblog.platform.service.ContainerStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * legacy-apiから移設(issue #695、C10-3)。コンテナ稼働状況・接続サービス稼働状況のポーリングAPIと
 * SSE配信を提供する。gatewayの{@code dashboard-status}ルート(services/gateway/src/main/resources/
 * application.yml)を経由する。docker-socket-proxyへのアクセスは本サービスのみに限定される
 * (Epic #551の方針、ContainerStatusServiceのJavadoc参照)。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ConnectedServiceStatusService connectedServiceStatusService;
    private final ConnectedServiceStatusBroadcaster connectedServiceStatusBroadcaster;
    private final ContainerStatusService containerStatusService;
    private final ContainerStatusBroadcaster containerStatusBroadcaster;
    private final AdminAuthorizationService adminAuthorizationService;

    public DashboardController(
            ConnectedServiceStatusService connectedServiceStatusService,
            ConnectedServiceStatusBroadcaster connectedServiceStatusBroadcaster,
            ContainerStatusService containerStatusService,
            ContainerStatusBroadcaster containerStatusBroadcaster,
            AdminAuthorizationService adminAuthorizationService) {
        this.connectedServiceStatusService = connectedServiceStatusService;
        this.connectedServiceStatusBroadcaster = connectedServiceStatusBroadcaster;
        this.containerStatusService = containerStatusService;
        this.containerStatusBroadcaster = containerStatusBroadcaster;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /**
     * 認可不要: ログイン後の共通ダッシュボードが出す「アプリが健全に動いているか」の要約
     * (issue #830)。より詳しい{@link #getServiceStatusDetail}のほうはadmin限定にしてある。
     */
    @GetMapping("/service-status")
    public List<ConnectedServiceStatusResponse> getServiceStatus() {
        return connectedServiceStatusService.checkAll();
    }

    /** issue #198: 稼働状況の変化をポーリングなしで受け取るためのSSE配信。 */
    /** 認可不要: {@link #getServiceStatus}のSSE版で、同じ要約を流すだけ(issue #830)。 */
    @GetMapping("/service-status/stream")
    public SseEmitter streamServiceStatus() {
        return connectedServiceStatusBroadcaster.subscribe();
    }

    /** issue #199: 応答時間・エラー内容・チェック対象URLなどの詳細診断情報。admin限定。 */
    @GetMapping("/service-status/detail")
    public List<ConnectedServiceStatusDetailResponse> getServiceStatusDetail() {
        adminAuthorizationService.requireAdmin();
        return connectedServiceStatusService.checkAllDetailed();
    }

    /** issue #280: このアプリを構成するDockerコンテナ(lbs-*)の稼働状況。 */
    /**
     * コンテナ名と稼働状況の一覧。**インフラの構成情報**なので admin 限定にする(issue #830)。
     *
     * <p>#816 のQAで、無効化された利用者のブラウザセッションが全コンテナ名と稼働状況を
     * 表示し続けることが確認されており、#830 はそれを塞ぐべき対象として名指ししている。
     * 同じコントローラの{@link #getServiceStatusDetail}が既に admin 限定である点とも揃う。
     */
    @GetMapping("/container-status")
    public List<ContainerStatusResponse> getContainerStatus() {
        adminAuthorizationService.requireAdmin();
        return containerStatusService.listAll();
    }

    /** issue #280: コンテナ稼働状況の変化をポーリングなしで受け取るためのSSE配信。 */
    /** {@link #getContainerStatus}のSSE版。同じ情報を流すので同じく admin 限定(issue #830)。 */
    @GetMapping("/container-status/stream")
    public SseEmitter streamContainerStatus() {
        adminAuthorizationService.requireAdmin();
        return containerStatusBroadcaster.subscribe();
    }
}
