package com.letsblog.common.messaging;

/**
 * ログメッセージキューイング(issue #466)用のRabbitMQ exchange/routing key定数。
 * プロデューサー(各サービス)とコンシューマー(log-writer)の両方が同じ値を参照する必要がある。
 * exchangeそのものの宣言(durable等)やキュー/バインディングは各サービスのRabbitMqConfigが担う。
 */
public final class LogExchanges {

    public static final String LOG_EXCHANGE = "letsblog.logs";
    public static final String ERROR_LOG_ROUTING_KEY = "log.error";
    public static final String OPERATION_LOG_ROUTING_KEY = "log.operation";
    public static final String AUDIT_LOG_ROUTING_KEY = "log.audit";

    private LogExchanges() {
    }
}
