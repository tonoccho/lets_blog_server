package com.letsblog.api.controller;

import com.letsblog.api.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.api.dto.ConnectedServiceStatusResponse;
import com.letsblog.api.dto.ContainerStatusResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ConnectedServiceStatusBroadcaster;
import com.letsblog.api.service.ConnectedServiceStatusService;
import com.letsblog.api.service.ContainerStatusBroadcaster;
import com.letsblog.api.service.ContainerStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

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

    @GetMapping("/service-status")
    public List<ConnectedServiceStatusResponse> getServiceStatus() {
        return connectedServiceStatusService.checkAll();
    }

    /** issue #198: 稼働状況の変化をポーリングなしで受け取るためのSSE配信。 */
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
    @GetMapping("/container-status")
    public List<ContainerStatusResponse> getContainerStatus() {
        return containerStatusService.listAll();
    }

    /** issue #280: コンテナ稼働状況の変化をポーリングなしで受け取るためのSSE配信。 */
    @GetMapping("/container-status/stream")
    public SseEmitter streamContainerStatus() {
        return containerStatusBroadcaster.subscribe();
    }
}
