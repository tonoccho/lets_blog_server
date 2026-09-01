package com.letsblog.common.messaging;

import com.letsblog.common.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;

/**
 * RabbitMQへ発行するメッセージに、発行元スレッドのMDCにある相関ID(issue #582)をヘッダとして
 * 付与する。各サービスの{@code RabbitTemplate}へ
 * {@code setBeforePublishPostProcessors(new CorrelationIdMessagePostProcessor())}として
 * 登録することで、{@code convertAndSend}の呼び出し元(DomainEventPublisher/AuditLogService等)を
 * 変更せずに全publish経路へ横断的に適用できる。
 *
 * <p>発行元スレッドにMDCが無い場合(HTTPリクエストの外、スケジューラ起点の処理等)は
 * ヘッダを付与しない。コンシューマー側の{@link CorrelationIdListenerAdvice}はヘッダが無ければ
 * MDCを設定しない(相関IDを持たないメッセージとして扱う)。
 */
public class CorrelationIdMessagePostProcessor implements MessagePostProcessor {

    @Override
    public Message postProcessMessage(Message message) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            message.getMessageProperties().setHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
        }
        return message;
    }
}
