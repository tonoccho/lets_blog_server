package com.letsblog.common.async;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * {@code @Async}ジョブの実行スレッドへ、投入したスレッド(HTTPリクエスト)のMDCを引き継ぐ
 * {@link TaskDecorator}(issue #1731)。処理ID(相関ID。MDCキー{@code correlationId})が実行スレッドの
 * ログと、そこからの同期呼び出し({@code SyncServiceClient}・注入されたRestClient.Builder)に載る。
 *
 * <p>実行スレッドはプールで使い回されるので、タスクの終了後は実行前のMDCへ戻す(前のタスクの処理IDを
 * 次のタスクへ持ち越さない)。投入元のMDCが空なら、実行中も空にする。
 * 各サービスの{@code ThreadPoolTaskExecutor} Beanに{@code setTaskDecorator}で設定する。
 */
public class MdcPropagatingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> submitterContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> workerContext = MDC.getCopyOfContextMap();
            apply(submitterContext);
            try {
                runnable.run();
            } finally {
                apply(workerContext);
            }
        };
    }

    private static void apply(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
