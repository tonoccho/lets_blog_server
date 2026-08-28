package com.letsblog.platform.controller;

import com.letsblog.platform.dto.AppSettingResponse;
import com.letsblog.platform.service.AppSettingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * adminユーザー限定のシステム設定画面(issue #403)向けAPI。プロジェクトに紐付かない業務系の
 * アプリ全体設定(外部LLMサービス連携・メール送信・Google OAuthクライアント・Webフロントの公開URL)を
 * 一覧・更新する。秘匿情報は値そのものを返さない(AppSettingService/SystemSettingControllerと同じ方針)。
 * 権限確認・監査ログ記録はAppSettingService側で行う(admin限定の操作)。legacy-apiから移設
 * (issue #693)。gatewayの{@code /api/system-settings/**}ルートを経由する。
 */
@RestController
@RequestMapping("/api/system-settings/app-settings")
public class AppSettingController {

    private final AppSettingService appSettingService;

    public AppSettingController(AppSettingService appSettingService) {
        this.appSettingService = appSettingService;
    }

    @GetMapping
    public List<AppSettingResponse> getAllSettings() {
        return appSettingService.getAllSettings().stream()
                .map(status -> new AppSettingResponse(
                        status.key(), status.label(), status.secret(), status.configured(),
                        status.source().name(), status.value()))
                .toList();
    }

    @PutMapping
    public ResponseEntity<Void> updateSettings(@RequestBody Map<String, String> settings) {
        appSettingService.updateSettings(settings);
        return ResponseEntity.noContent().build();
    }
}
