package com.letsblog.api.controller;

import com.letsblog.api.dto.BraveSearchApiKeyStatusResponse;
import com.letsblog.api.dto.SetBraveSearchApiKeyRequest;
import com.letsblog.api.service.SystemSettingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト/サイトに紐付かないアプリ全体のグローバル設定のWeb管理画面向けAPI。
 * 値そのものは返さず、設定済みかどうか・設定元(DB/環境変数)のみを返す
 * (site credentialsのconfiguredSecretFieldsと同じ「秘匿値は見せない」方針)。
 * 更新・削除の権限確認・監査ログ記録はSystemSettingService側で行う(admin限定の操作)。
 */
@RestController
@RequestMapping("/api/system-settings")
public class SystemSettingController {

    private final SystemSettingService systemSettingService;

    public SystemSettingController(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    @GetMapping("/brave-search-api-key")
    public BraveSearchApiKeyStatusResponse getBraveSearchApiKeyStatus() {
        SystemSettingService.BraveSearchApiKeyStatus status = systemSettingService.getBraveSearchApiKeyStatus();
        return new BraveSearchApiKeyStatusResponse(status.configured(), status.source().name());
    }

    @PutMapping("/brave-search-api-key")
    public ResponseEntity<Void> setBraveSearchApiKey(@Valid @RequestBody SetBraveSearchApiKeyRequest request) {
        systemSettingService.setBraveSearchApiKey(request.apiKey());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/brave-search-api-key")
    public ResponseEntity<Void> clearBraveSearchApiKey() {
        systemSettingService.clearBraveSearchApiKey();
        return ResponseEntity.noContent().build();
    }
}
