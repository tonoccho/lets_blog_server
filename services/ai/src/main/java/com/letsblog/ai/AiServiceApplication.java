package com.letsblog.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ai-service(issue #574)。LLM呼び出し(下書き/校正/要約/タグ提案/セクション生成/Ask AI)と
 * 記事プラン(壁打ち)、および両者から共有されるジョブ履歴(generation_jobs)をlegacy-apiから
 * 抽出したもの。lbs_aiスキーマ(ADR-0004)を所有する。
 *
 * <p>scanBasePackagesにcom.letsblog.commonを含めるのは、CredentialCipher(project_ai_settingsの
 * Brave Search APIキー暗号化に使う)がlbs-commonライブラリのBeanのため(legacy-api/media-serviceと
 * 同じ理由)。
 */
@SpringBootApplication(scanBasePackages = {"com.letsblog.ai", "com.letsblog.common"})
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
