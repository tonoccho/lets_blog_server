package com.letsblog.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * analytics-service(issue #578)。Google Analytics/AdSense連携(OAuthの資格情報保管、GA4 Data API/
 * AdSense Management APIのレポート取得)をlegacy-apiから抽出したもの。OAuthの資格情報を扱うため、
 * 他ドメインから分離して保管する。lbs_analyticsスキーマ(ADR-0004)を所有する。
 *
 * <p>scanBasePackagesにcom.letsblog.commonを含めるのは、CredentialCipher(analytics_credentialsの
 * 各暗号化カラムの暗号化に使う)がlbs-commonライブラリのBeanのため(legacy-api/ai-serviceと同じ理由)。
 */
@SpringBootApplication(scanBasePackages = {"com.letsblog.analytics", "com.letsblog.common"})
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
