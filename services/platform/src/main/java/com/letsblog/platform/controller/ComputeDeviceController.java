package com.letsblog.platform.controller;

import com.letsblog.platform.dto.ApplyComputeDeviceRequest;
import com.letsblog.platform.dto.ComputeDeviceStatusResponse;
import com.letsblog.platform.service.AdminAuthorizationService;
import com.letsblog.platform.service.ComputeDeviceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理画面から演算デバイス(GPU / CPU)を切り替えるAPI(issue #1399)。管理者だけが参照・適用できる
 * (既存の管理者向け設定と同じ{@code requireAdmin}の経路)。gatewayの{@code /api/system-settings/**}
 * ルートを経由する。適用は受け付けたらすぐ202を返し、進行は{@code GET}で取得する。
 */
@RestController
@RequestMapping("/api/system-settings/compute-devices")
public class ComputeDeviceController {

    private final ComputeDeviceService computeDeviceService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ComputeDeviceController(
            ComputeDeviceService computeDeviceService, AdminAuthorizationService adminAuthorizationService) {
        this.computeDeviceService = computeDeviceService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping("/{target}")
    public ComputeDeviceStatusResponse getStatus(@PathVariable String target) {
        adminAuthorizationService.requireAdmin();
        return computeDeviceService.getStatus(target);
    }

    @PostMapping("/{target}/apply")
    public ResponseEntity<ComputeDeviceStatusResponse> apply(
            @PathVariable String target, @Valid @RequestBody ApplyComputeDeviceRequest request) {
        adminAuthorizationService.requireAdmin();
        return ResponseEntity.accepted().body(computeDeviceService.apply(target, request.device()));
    }
}
