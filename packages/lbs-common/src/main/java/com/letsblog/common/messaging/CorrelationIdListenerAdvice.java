package com.letsblog.common.messaging;

import com.letsblog.common.web.CorrelationIdFilter;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;

import java.util.UUID;

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
 * <p>ヘッダが無い(または空白の)メッセージ(相関ID導入前に発行されたもの、ヘッダを付けない発行元等)は、
 * 新しい処理IDを採番して処理する(issue #1735)。メッセージ処理はすべて処理IDを持つ。
 *
 * <p>メッセージ1件の処理ごとに、完了行を1行出す。本文に{@code key=value}で
 * {@code queue=キュー名 duration_ms=.. outcome=success|failure}を書き、失敗時は例外のクラス名だけを
 * {@code error=}に載せる(メッセージは秘密や改行を含みうるため出さない)。成功はINFO、例外で終わったときは
 * WARNで、例外は握りつぶさず再送出する。本adviceはリトライadviceの外側(chainの先頭)に置かれるため、
 * リトライを使い切って最終的に例外になった配信1件につき1行になる(リトライ→DLQの流れは変えない)。
 */
public class CorrelationIdListenerAdvice implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdListenerAdvice.class);
    private static final String UNKNOWN_QUEUE = "unknown";
    private static final long NANOS_PER_MILLI = 1_000_000L;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Message message = extractMessage(invocation.getArguments());
        String correlationId = headerCorrelationId(message);
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        String queue = queueOf(message);

        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        long start = System.nanoTime();
        try {
            Object result = invocation.proceed();
            log.info("message processed: queue={} duration_ms={} outcome=success", queue, elapsedMs(start));
            return result;
        } catch (Throwable failure) {
            log.warn("message processed: queue={} duration_ms={} outcome=failure error={}",
                    queue, elapsedMs(start), failure.getClass().getSimpleName());
            throw failure;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / NANOS_PER_MILLI;
    }

    private static Message extractMessage(Object[] arguments) {
        for (Object argument : arguments) {
            if (argument instanceof Message message) {
                return message;
            }
        }
        return null;
    }

    private static String headerCorrelationId(Message message) {
        if (message == null) {
            return null;
        }
        Object header = message.getMessageProperties().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        if (header == null || header.toString().isBlank()) {
            return null;
        }
        return header.toString();
    }

    private static String queueOf(Message message) {
        if (message == null) {
            return UNKNOWN_QUEUE;
        }
        String queue = message.getMessageProperties().getConsumerQueue();
        return queue != null ? queue : UNKNOWN_QUEUE;
    }
}
