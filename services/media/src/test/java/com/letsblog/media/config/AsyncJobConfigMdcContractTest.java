package com.letsblog.media.config;

import com.letsblog.common.testfixtures.AsyncExecutorMdcContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** media-service の全Executor Beanが処理ID(MDC)を引き継ぐ(issue #1731)。 */
@DisplayName("media-service: 全ThreadPoolTaskExecutor BeanがMDCの処理IDを引き継ぐ(issue #1731)")
class AsyncJobConfigMdcContractTest {

    @Test
    void 全Executor_Beanが投入時のMDCを引き継ぎ実行後に持ち越さない() throws Exception {
        AsyncExecutorMdcContract.assertAllExecutorBeansPropagateMdc(AsyncJobConfig.class);
    }
}
