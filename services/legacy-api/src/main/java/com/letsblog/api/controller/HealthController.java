package com.letsblog.api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    /**
     * 認可不要: SecurityConfig の PUBLIC_PATHS に含まれるヘルスチェック用の公開パス(issue #830)。
     * 監視・コンテナのヘルスチェックが認証なしで叩く前提のもの。
     */
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
