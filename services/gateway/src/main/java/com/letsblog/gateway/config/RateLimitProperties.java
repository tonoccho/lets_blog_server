package com.letsblog.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * レート制限バケットごとの静的な既定値(#560。旧legacy-apiのRateLimitInterceptorから移設)。
 * アップロード系エンドポイントを管理画面から動的に変更する機能(旧AppSettingService経由)は
 * gatewayがDBを持たないため未対応 - follow-up issueで再検討する(#560 PR参照)。
 *
 * <p>{@code apiInternal} は、lbs-net内部からgatewayを直接叩く呼び出し(主にWebのBFF)専用の
 * バケット(#749)。issue #584でBFFの全リクエストがgatewayを通るようになり、外部クライアントと
 * 同じ枠を食い合うと画面遷移だけで枯渇するため、外部({@code apiGlobal})とは別枠にして
 * 上限も引き上げている。どのリクエストがどちらに入るかは
 * {@link RateLimitWebFilter} のJavadocを参照。
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private Bucket apiGlobal = new Bucket(100, Duration.ofMinutes(1));
    private Bucket apiInternal = new Bucket(600, Duration.ofMinutes(1));
    private Bucket authEndpoint = new Bucket(5, Duration.ofMinutes(1));
    private Bucket operationLogEndpoint = new Bucket(300, Duration.ofMinutes(1));
    private Bucket uploadEndpoint = new Bucket(10, Duration.ofHours(1));

    public Bucket getApiGlobal() {
        return apiGlobal;
    }

    public void setApiGlobal(Bucket apiGlobal) {
        this.apiGlobal = apiGlobal;
    }

    public Bucket getApiInternal() {
        return apiInternal;
    }

    public void setApiInternal(Bucket apiInternal) {
        this.apiInternal = apiInternal;
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
