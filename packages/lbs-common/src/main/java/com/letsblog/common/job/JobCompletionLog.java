package com.letsblog.common.job;

import org.slf4j.Logger;

/**
 * 非同期ジョブ1件の終了時に、完了行を1行出す(issue #1731)。本文は{@code key=value}で、
 * 種別({@code kind})・{@code job_id}({@code generation_jobs.id}があるときだけ)・{@code duration_ms}・
 * {@code outcome}(成功は{@code success}、失敗は例外クラス名)を持つ。処理IDとサービスはMDCと
 * ログの共通項目に載るので本文には書かない。成功は INFO、失敗は WARN。
 *
 * <pre>
 * var timer = JobCompletionLog.start(log, "text_generation", jobId);
 * try { ...; timer.success(); } catch (RuntimeException e) { timer.failure(e); ... }
 * </pre>
 *
 * 1つのタイマーが出す行は1行だけ(最初の{@code success}/{@code failure}が勝つ)。
 */
public final class JobCompletionLog {

    private JobCompletionLog() {
    }

    public static Timer start(Logger log, String kind, Long jobId) {
        return new Timer(log, kind, jobId);
    }

    /** 開始時刻を持ち、終了時に完了行を出す。 */
    public static final class Timer {
        private final Logger log;
        private final String kind;
        private final Long jobId;
        private final long startedAtNanos = System.nanoTime();
        private boolean finished;

        private Timer(Logger log, String kind, Long jobId) {
            this.log = log;
            this.kind = kind;
            this.jobId = jobId;
        }

        public void success() {
            finish("success", false);
        }

        public void failure(Throwable cause) {
            finish(cause.getClass().getSimpleName(), true);
        }

        /** 例外を伴わない失敗(全項目が失敗してジョブをfailedにした等)。{@code outcome}は呼び出し側が名付ける。 */
        public void failure(String outcome) {
            finish(outcome, true);
        }

        private synchronized void finish(String outcome, boolean failed) {
            if (finished) {
                return;
            }
            finished = true;
            long durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
            String jobPart = jobId == null ? "" : " job_id=" + jobId;
            if (failed) {
                log.warn("job completed: kind={}{} duration_ms={} outcome={}", kind, jobPart, durationMs, outcome);
            } else {
                log.info("job completed: kind={}{} duration_ms={} outcome={}", kind, jobPart, durationMs, outcome);
            }
        }
    }
}
