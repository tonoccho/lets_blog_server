package com.letsblog.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * レート制限バケットごとの静的な既定値(#560。旧legacy-apiのRateLimitInterceptorから移設)。
 * アップロード系エンドポイントを管理画面から動的に変更する機能(旧AppSettingService経由)は
 * gatewayがDBを持たないため未対応 - follow-up issueで再検討する(#560 PR参照)。
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private Bucket apiGlobal = new Bucket(100, Duration.ofMinutes(1));
    private Bucket authEndpoint = new Bucket(5, Duration.ofMinutes(1));
    private Bucket operationLogEndpoint = new Bucket(300, Duration.ofMinutes(1));
    private Bucket uploadEndpoint = new Bucket(10, Duration.ofHours(1));

    public Bucket getApiGlobal() {
        return apiGlobal;
    }

    public void setApiGlobal(Bucket apiGlobal) {
        this.apiGlobal = apiGlobal;
    }

    public Bucket getAuthEndpoint() {
        return authEndpoint;
    }

    public void setAuthEndpoint(Bucket authEndpoint) {
        this.authEndpoint = authEndpoint;
    }

    public Bucket getOperationLogEndpoint() {
        return operationLogEndpoint;
    }

    public void setOperationLogEndpoint(Bucket operationLogEndpoint) {
        this.operationLogEndpoint = operationLogEndpoint;
    }

    public Bucket getUploadEndpoint() {
        return uploadEndpoint;
    }

    public void setUploadEndpoint(Bucket uploadEndpoint) {
        this.uploadEndpoint = uploadEndpoint;
    }

    public static class Bucket {
        private int limitForPeriod;
        private Duration limitRefreshPeriod;

        public Bucket() {
        }

        public Bucket(int limitForPeriod, Duration limitRefreshPeriod) {
            this.limitForPeriod = limitForPeriod;
            this.limitRefreshPeriod = limitRefreshPeriod;
        }

        public int getLimitForPeriod() {
            return limitForPeriod;
        }

        public void setLimitForPeriod(int limitForPeriod) {
            this.limitForPeriod = limitForPeriod;
        }

        public Duration getLimitRefreshPeriod() {
            return limitRefreshPeriod;
        }

        public void setLimitRefreshPeriod(Duration limitRefreshPeriod) {
            this.limitRefreshPeriod = limitRefreshPeriod;
        }
    }
}
