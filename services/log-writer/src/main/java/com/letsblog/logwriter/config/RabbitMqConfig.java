package com.letsblog.logwriter.config;

import com.letsblog.common.messaging.CorrelationIdListenerAdvice;
import com.letsblog.common.messaging.LogExchanges;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ログメッセージキューイング(issue #466)用のRabbitMQ設定。log-writerはコンシューマー側として
 * exchange/キュー/バインディングの宣言を所有する(apiサーバーはexchangeのみを宣言してpublishする)。
 * exchange名/型/durableはapi側のRabbitMqConfigと一致させる必要がある
 * (exchange名/routing keyはlbs-common({@link LogExchanges})でサービス間共有する)。
 *
 * <p><b>dead-letter配線(issue #1059)。</b> 3キュー(error-log/operation-log/audit-log)とも
 * {@code x-dead-letter-exchange}を{@link #LOG_DLX}へ設定し、対応するDLQ(キュー名+
 * {@link #DLQ_SUFFIX})へ結線する。{@code spring.rabbitmq.listener.simple.retry}
 * (application.yml、issue #1059)で有限回リトライした後、既定の{@code RejectAndDontRequeueRecoverer}
 * が{@code AmqpRejectAndDontRequeueException}を投げてrequeueせず終わらせる
 * ({@code default-requeue-rejected}の対象外になる)ため、DLXへdead-letterされる。
 * {@code message}列のNOT NULL違反のような永続的な失敗は、これでrequeueループにならず
 * 有限回で観測可能な場所(DLQ)に残る。content-serviceのletsblog.events向けDLQ命名規約
 * ({@code EventExchanges})と同じ考え方だが、letsblog.logsはlog-writerのみが購読するため
 * 定数はlbs-commonへ上げずここに閉じる。
 *
 * <p>content-serviceの{@code RabbitMqConfig}は{@code configurer.configure(...)}が
 * {@code setAdviceChain}でリトライadviceを設定した直後に、{@code factory.setAdviceChain(new
 * CorrelationIdListenerAdvice())}で丸ごと上書きしてしまっており、リトライadvice自体が
 * 消えてしまう(setAdviceChainは可変長引数を丸ごと置き換える)。本サービスでは
 * {@link #prependCorrelationAdvice(Advice[])}で{@code configurer.configure(...)}が設定した
 * chainを読み出し(直後の{@code getAdviceChain()})、先頭に{@link CorrelationIdListenerAdvice}を
 * 追加してから{@code setAdviceChain}する(相関IDを先に設定してからリトライ判定に入る、
 * Issueの実装ノート参照)。
 */
@Configuration
public class RabbitMqConfig {

    public static final String ERROR_LOG_QUEUE = "error-logs.queue";
    public static final String OPERATION_LOG_QUEUE = "operation-logs.queue";
    public static final String AUDIT_LOG_QUEUE = "audit-logs.queue";

    /** リトライ上限超過時にdead-letterされるログ用DLX(issue #1059)。 */
    public static final String LOG_DLX = "letsblog.logs.dlx";

    /**
     * キュー名からDLQ名を作る接尾辞の規約(content-serviceの{@code EventExchanges.DLQ_SUFFIX}と同じ)。
     * DLQのルーティングキーもこのDLQ名をそのまま使う(DLXへdead-letterされたメッセージのrouting keyは
     * 元のキュー名に書き換わるため、DLQ側はキュー名と同じルーティングキーでバインドする)。
     */
    public static final String DLQ_SUFFIX = ".dlq";

    @Bean
    public TopicExchange logsExchange() {
        return new TopicExchange(LogExchanges.LOG_EXCHANGE, true, false);
    }

    /** dead-letter先のtopic exchange(issue #1059)。 */
    @Bean
    public TopicExchange logsDlx() {
        return new TopicExchange(LOG_DLX, true, false);
    }

    @Bean
    public Queue errorLogQueue() {
        return mainQueue(ERROR_LOG_QUEUE);
    }

    @Bean
    public Queue operationLogQueue() {
        return mainQueue(OPERATION_LOG_QUEUE);
    }

    @Bean
    public Queue auditLogQueue() {
        return mainQueue(AUDIT_LOG_QUEUE);
    }

    @Bean
    public Queue errorLogDlq() {
        return dlq(ERROR_LOG_QUEUE);
    }

    @Bean
    public Queue operationLogDlq() {
        return dlq(OPERATION_LOG_QUEUE);
    }

    @Bean
    public Queue auditLogDlq() {
        return dlq(AUDIT_LOG_QUEUE);
    }

    @Bean
    public Binding errorLogBinding(Queue errorLogQueue, TopicExchange logsExchange) {
        return BindingBuilder.bind(errorLogQueue).to(logsExchange).with(LogExchanges.ERROR_LOG_ROUTING_KEY);
    }

    @Bean
    public Binding operationLogBinding(Queue operationLogQueue, TopicExchange logsExchange) {
        return BindingBuilder.bind(operationLogQueue).to(logsExchange).with(LogExchanges.OPERATION_LOG_ROUTING_KEY);
    }

    @Bean
    public Binding auditLogBinding(Queue auditLogQueue, TopicExchange logsExchange) {
        return BindingBuilder.bind(auditLogQueue).to(logsExchange).with(LogExchanges.AUDIT_LOG_ROUTING_KEY);
    }

    @Bean
    public Binding errorLogDlqBinding(Queue errorLogDlq, TopicExchange logsDlx) {
        return dlqBinding(errorLogDlq, logsDlx, ERROR_LOG_QUEUE);
    }

    @Bean
    public Binding operationLogDlqBinding(Queue operationLogDlq, TopicExchange logsDlx) {
        return dlqBinding(operationLogDlq, logsDlx, OPERATION_LOG_QUEUE);
    }

    @Bean
    public Binding auditLogDlqBinding(Queue auditLogDlq, TopicExchange logsDlx) {
        return dlqBinding(auditLogDlq, logsDlx, AUDIT_LOG_QUEUE);
    }

    /**
     * リトライ上限超過時に{@link #LOG_DLX}へdead-letterされる、通常キュー(durable)を作る
     * (issue #1059)。
     *
     * <p><b>注意(稼働中環境への適用):</b> RabbitMQはキューの引数(ここでは
     * {@code x-dead-letter-exchange}/{@code x-dead-letter-routing-key})を再宣言で変更できない
     * ({@code PRECONDITION_FAILED}になる)。すでに引数無しで存在するキュー(このサービスの
     * これまでの稼働環境)へ適用するには、対象キューを一度削除してから本サービスを再起動し、
     * 新しい引数で再宣言させる必要がある(削除中に溜まったメッセージは失われる。issueの
     * 実装ノート参照)。
     */
    Queue mainQueue(String queueName) {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", LOG_DLX)
                .withArgument("x-dead-letter-routing-key", queueName + DLQ_SUFFIX)
                .build();
    }

    /** 対応する通常キューのDLQ(durable)を作る。キュー名は{@code <queueName>.dlq}。 */
    Queue dlq(String queueName) {
        return QueueBuilder.durable(queueName + DLQ_SUFFIX).build();
    }

    Binding dlqBinding(Queue dlq, TopicExchange logsDlx, String queueName) {
        return BindingBuilder.bind(dlq).to(logsDlx).with(queueName + DLQ_SUFFIX);
    }

    /**
     * メッセージ型(lbs-commonの{@code com.letsblog.common.messaging}パッケージ)はプロデューサー・
     * コンシューマー双方で同じクラスを参照するようになったが、送信側が付与する__TypeId__ヘッダー
     * (クラス名)による型解決には依存せず、引き続き@RabbitListenerメソッドの引数型から推論させる
     * (プロデューサーが増えても解決方式を変えずに済むため)。
     */
    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        converter.setTypePrecedence(JacksonJavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }

    /**
     * {@link SimpleRabbitListenerContainerFactoryConfigurer}経由でapplication.ymlの
     * {@code spring.rabbitmq.listener.simple.retry}設定(issue #1059)を適用したうえで、
     * 相関ID(issue #582)のMDC設定adviceをその手前に足したリスナーコンテナファクトリ。
     * {@code LogMessageListener}の全{@code @RabbitListener}メソッドが使う。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            JacksonJsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(converter);
        // configure()が設定したリトライadvice chainを丸ごと置き換えず、相関IDのMDC設定を
        // 先頭に足す(消さずに共存させる、issueの実装ノート参照)。
        factory.setAdviceChain(prependCorrelationAdvice(factory.getAdviceChain()));
        return factory;
    }

    /**
     * 既存のadvice chainの先頭に{@link CorrelationIdListenerAdvice}を追加する(issue #1059)。
     * 相関IDのMDC設定を先に行ってからリトライの成否判定に入るようにする(順序が重要、
     * Issueの実装ノート参照)。
     */
    Advice[] prependCorrelationAdvice(Advice[] existing) {
        int existingLength = existing != null ? existing.length : 0;
        Advice[] combined = new Advice[existingLength + 1];
        combined[0] = new CorrelationIdListenerAdvice();
        if (existingLength > 0) {
            System.arraycopy(existing, 0, combined, 1, existingLength);
        }
        return combined;
    }
}
