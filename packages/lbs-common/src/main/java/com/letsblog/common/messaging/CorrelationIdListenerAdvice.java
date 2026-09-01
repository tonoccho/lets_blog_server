package com.letsblog.common.messaging;

import com.letsblog.common.web.CorrelationIdFilter;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;

/**
 * {@code @RabbitListener}メッセージ処理中、発行元が{@link CorrelationIdMessagePostProcessor}で
 * 付与した相関ID(issue #582)ヘッダをMDCへ設定し、非同期処理のログにも相関IDを載せる。
 *
 * <p>{@code SimpleRabbitListenerContainerFactory#setAdviceChain}に登録して使う。Spring AMQPの
 * リスナーコンテナは{@code ChannelAwareMessageListener#onMessage(Message, Channel)}をこの
 * アドバイスチェーンでラップして呼び出すため、{@link MethodInvocation#getArguments()}から
 * 生の{@link Message}(ヘッダ含む)を取得できる(引数型からの推論による{@code @RabbitListener}の
 * 型変換とは別に、コンテナは変換前の生メッセージも保持している)。
 *
 * <pre>{@code
 * factory.setAdviceChain(new CorrelationIdListenerAdvice());
 * }</pre>
 *
 * <p>ヘッダが無いメッセージ(相関ID導入前に発行されたもの等)はMDCを設定せずそのまま処理する。
 */
public class CorrelationIdListenerAdvice implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        String correlationId = extractCorrelationId(invocation.getArguments());
        if (correlationId == null) {
            return invocation.proceed();
        }

        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        try {
            return invocation.proceed();
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private String extractCorrelationId(Object[] arguments) {
        for (Object argument : arguments) {
            if (argument instanceof Message message) {
                Object header = message.getMessageProperties().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
                return header != null ? header.toString() : null;
            }
        }
        return null;
    }
}
