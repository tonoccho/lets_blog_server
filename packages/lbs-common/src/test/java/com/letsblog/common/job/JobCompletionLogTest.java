package com.letsblog.common.job;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** ジョブ1件の完了行(種別・jobId・duration_ms・outcome)の書式と水準(issue #1731)。 */
@DisplayName("JobCompletionLog: ジョブの完了行(issue #1731)")
class JobCompletionLogTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("job-completion-log-test");
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("成功は INFO で、種別・job_id・duration_ms・outcome=success を key=value で1行出す")
    void success() {
        JobCompletionLog.start(logger, "text_generation", 12L).success();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .matches("job completed: kind=text_generation job_id=12 duration_ms=\\d+ outcome=success");
    }

    @Test
    @DisplayName("失敗は WARN で、outcome は例外クラス名")
    void failure() {
        JobCompletionLog.start(logger, "image_generation", 7L).failure(new IllegalStateException("x"));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .matches("job completed: kind=image_generation job_id=7 duration_ms=\\d+ outcome=IllegalStateException");
    }

    @Test
    @DisplayName("jobId が無い(null)ジョブは job_id を出さない")
    void withoutJobId() {
        JobCompletionLog.start(logger, "image_tagging", null).success();

        assertThat(appender.list.get(0).getFormattedMessage())
                .matches("job completed: kind=image_tagging duration_ms=\\d+ outcome=success");
    }

    @Test
    @DisplayName("success と failure を続けて呼んでも完了行は1行だけ")
    void finishesOnlyOnce() {
        JobCompletionLog.Timer timer = JobCompletionLog.start(logger, "k", 1L);
        timer.success();
        timer.failure(new RuntimeException("late"));
        timer.success();

        assertThat(appender.list).hasSize(1);
    }
}
