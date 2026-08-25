package com.letsblog.content.config;

import com.letsblog.common.messaging.EventExchanges;
import com.letsblog.common.messaging.LogExchanges;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.JacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * letsblog.logs(issue #466)・letsblog.events(issue #580)用のRabbitMQ設定。content-serviceは
 * letsblog.logsのプロデューサー(AuditLogService)であると同時に、letsblog.eventsの6ルーティングキー
 * 全ての購読者でもある(project.deleted/site.deletedは自スキーマの実データ削除、他4種は冪等な
 * 受信記録のみ、docs/EVENT_DRIVEN_ARCHITECTURE.md参照)。
 *
 * <p>「誰がキューを宣言するか」はlog-writerと同じ方針(コンシューマーが自分の読むキュー/バインディング/
 * DLQを宣言する)。DLQはRabbitMQ標準のdead-letter-exchangeパターン(各キューにx-dead-letter-exchange/
 * x-dead-letter-routing-keyを設定し、DLX(letsblog.events.dlx)経由でDLQへ落とす)。配送失敗時の
 * リトライ回数・間隔はapplication.ymlのspring.rabbitmq.listener.simple.retryで設定し、リトライ上限
 * 超過後は{@code RejectAndDontRequeueRecoverer}(既定)によりrequeueされずDLXへdead-letterされる。
 */
@Configuration
public class RabbitMqConfig {

    public static final String POST_PUBLISHED_QUEUE = "content.post-published.queue";
    public static final String POST_DELETED_QUEUE = "content.post-deleted.queue";
    public static final String IMAGE_GENERATED_QUEUE = "content.image-generated.queue";
    public static final String PROJECT_DELETED_QUEUE = "content.project-deleted.queue";
    public static final String SITE_DELETED_QUEUE = "content.site-deleted.queue";
    public static final String USER_DEACTIVATED_QUEUE = "content.user-deactivated.queue";

    @Bean
    public TopicExchange logsExchange() {
        return new TopicExchange(LogExchanges.LOG_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EventExchanges.EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange eventsDlx() {
        return new TopicExchange(EventExchanges.EVENTS_DLX, true, false);
    }

    // --- post.published ---

    @Bean
    public Queue postPublishedQueue() {
        return mainQueue(POST_PUBLISHED_QUEUE);
    }

    @Bean
    public Binding postPublishedBinding(Queue postPublishedQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(postPublishedQueue).to(eventsExchange)
                .with(EventExchanges.POST_PUBLISHED_ROUTING_KEY);
    }

    @Bean
    public Queue postPublishedDlq() {
        return dlq(POST_PUBLISHED_QUEUE);
    }

    @Bean
    public Binding postPublishedDlqBinding(Queue postPublishedDlq, TopicExchange eventsDlx) {
        return dlqBinding(postPublishedDlq, eventsDlx, POST_PUBLISHED_QUEUE);
    }

    // --- post.deleted ---

    @Bean
    public Queue postDeletedQueue() {
        return mainQueue(POST_DELETED_QUEUE);
    }

    @Bean
    public Binding postDeletedBinding(Queue postDeletedQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(postDeletedQueue).to(eventsExchange)
                .with(EventExchanges.POST_DELETED_ROUTING_KEY);
    }

    @Bean
    public Queue postDeletedDlq() {
        return dlq(POST_DELETED_QUEUE);
    }

    @Bean
    public Binding postDeletedDlqBinding(Queue postDeletedDlq, TopicExchange eventsDlx) {
        return dlqBinding(postDeletedDlq, eventsDlx, POST_DELETED_QUEUE);
    }

    // --- image.generated ---

    @Bean
    public Queue imageGeneratedQueue() {
        return mainQueue(IMAGE_GENERATED_QUEUE);
    }

    @Bean
    public Binding imageGeneratedBinding(Queue imageGeneratedQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(imageGeneratedQueue).to(eventsExchange)
                .with(EventExchanges.IMAGE_GENERATED_ROUTING_KEY);
    }

    @Bean
    public Queue imageGeneratedDlq() {
        return dlq(IMAGE_GENERATED_QUEUE);
    }

    @Bean
    public Binding imageGeneratedDlqBinding(Queue imageGeneratedDlq, TopicExchange eventsDlx) {
        return dlqBinding(imageGeneratedDlq, eventsDlx, IMAGE_GENERATED_QUEUE);
    }

    // --- project.deleted ---

    @Bean
    public Queue projectDeletedQueue() {
        return mainQueue(PROJECT_DELETED_QUEUE);
    }

    @Bean
    public Binding projectDeletedBinding(Queue projectDeletedQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(projectDeletedQueue).to(eventsExchange)
                .with(EventExchanges.PROJECT_DELETED_ROUTING_KEY);
    }

    @Bean
    public Queue projectDeletedDlq() {
        return dlq(PROJECT_DELETED_QUEUE);
    }

    @Bean
    public Binding projectDeletedDlqBinding(Queue projectDeletedDlq, TopicExchange eventsDlx) {
        return dlqBinding(projectDeletedDlq, eventsDlx, PROJECT_DELETED_QUEUE);
    }

    // --- site.deleted ---

    @Bean
    public Queue siteDeletedQueue() {
        return mainQueue(SITE_DELETED_QUEUE);
    }

    @Bean
    public Binding siteDeletedBinding(Queue siteDeletedQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(siteDeletedQueue).to(eventsExchange)
                .with(EventExchanges.SITE_DELETED_ROUTING_KEY);
    }

    @Bean
    public Queue siteDeletedDlq() {
        return dlq(SITE_DELETED_QUEUE);
    }

    @Bean
    public Binding siteDeletedDlqBinding(Queue siteDeletedDlq, TopicExchange eventsDlx) {
        return dlqBinding(siteDeletedDlq, eventsDlx, SITE_DELETED_QUEUE);
    }

    // --- user.deactivated ---

    @Bean
    public Queue userDeactivatedQueue() {
        return mainQueue(USER_DEACTIVATED_QUEUE);
    }

    @Bean
    public Binding userDeactivatedBinding(Queue userDeactivatedQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(userDeactivatedQueue).to(eventsExchange)
                .with(EventExchanges.USER_DEACTIVATED_ROUTING_KEY);
    }

    @Bean
    public Queue userDeactivatedDlq() {
        return dlq(USER_DEACTIVATED_QUEUE);
    }

    @Bean
    public Binding userDeactivatedDlqBinding(Queue userDeactivatedDlq, TopicExchange eventsDlx) {
        return dlqBinding(userDeactivatedDlq, eventsDlx, USER_DEACTIVATED_QUEUE);
    }

    /** リトライ上限超過時にeventsDlxへdead-letterされる、通常キュー(durable)を作る。 */
    private Queue mainQueue(String queueName) {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", EventExchanges.EVENTS_DLX)
                .withArgument("x-dead-letter-routing-key", queueName + EventExchanges.DLQ_SUFFIX)
                .build();
    }

    /** 対応する通常キューのDLQ(durable)を作る。キュー名は{@code <queueName>.dlq}。 */
    private Queue dlq(String queueName) {
        return QueueBuilder.durable(queueName + EventExchanges.DLQ_SUFFIX).build();
    }

    private Binding dlqBinding(Queue dlq, TopicExchange eventsDlx, String queueName) {
        return BindingBuilder.bind(dlq).to(eventsDlx).with(queueName + EventExchanges.DLQ_SUFFIX);
    }

    /**
     * メッセージ型(lbs-commonの{@code com.letsblog.common.messaging}パッケージ)の解決は
     * @RabbitListenerメソッドの引数型からの推論に統一する(log-writerのRabbitMqConfigと同じ方針、
     * 送信側の__TypeId__ヘッダーに依存しない)。
     */
    @Bean
    public JacksonJsonMessageConverter jacksonJsonMessageConverter() {
        JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
        converter.setTypePrecedence(JacksonJavaTypeMapper.TypePrecedence.INFERRED);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, JacksonJsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }

    /**
     * {@link SimpleRabbitListenerContainerFactoryConfigurer}経由でapplication.ymlの
     * spring.rabbitmq.listener.simple.retry設定(issue #580のリトライ回数・DLQ方針)を適用した
     * リスナーコンテナファクトリ。EventMessageListenerの全{@code @RabbitListener}メソッドが使う。
     */
    @Bean
    public RabbitListenerContainerFactory<?> eventsListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            JacksonJsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(converter);
        return factory;
    }
}
