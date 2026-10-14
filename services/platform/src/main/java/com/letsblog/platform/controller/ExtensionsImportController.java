package com.letsblog.platform.controller;

import com.letsblog.platform.service.ExtensionsConnectionImportService;
import com.letsblog.platform.service.ExtensionsConnectionImportService.ApplyResult;
import com.letsblog.platform.service.ExtensionsConnectionImportService.FetchResult;
import com.letsblog.platform.service.ExtensionsConnectionImportService.Route;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * extensions のポータルから接続先をシステム設定へ取り込む管理者向けAPI(issue #1783)。
 * 認可(admin限定)はサービス層の requireAdmin が担う。
 */
@RestController
@RequestMapping("/api/system-settings/app-settings/extensions-import")
public class ExtensionsImportController {

    public record FetchRequest(String portalUrl, String route) {
    }

    public record ApplyRequest(String portalUrl, Map<String, String> values) {
    }

    private final ExtensionsConnectionImportService extensionsConnectionImportService;

    public ExtensionsImportController(ExtensionsConnectionImportService extensionsConnectionImportService) {
        this.extensionsConnectionImportService = extensionsConnectionImportService;
    }

    @PostMapping("/fetch")
    public FetchResult fetch(@RequestBody FetchRequest request) {
        return extensionsConnectionImportService.fetch(request.portalUrl(), parseRoute(request.route()));
    }

    @PostMapping("/apply")
    public ApplyResult apply(@RequestBody ApplyRequest request) {
        return extensionsConnectionImportService.apply(request.portalUrl(), request.values());
    }

    private static Route parseRoute(String route) {
        try {
            return Route.valueOf(route);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("経路は SAME_PC / OTHER_HOST のいずれかを指定してください", e);
        }
    }
}
