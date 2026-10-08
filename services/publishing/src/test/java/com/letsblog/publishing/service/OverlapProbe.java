package com.letsblog.publishing.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 環境ごとの呼び出しが「重なって」走ったことをユニットテストで確かめるための道具(issue #1687)。
 * 呼び出しが{@code expected}本そろうまで各呼び出しをここで待たせる。逐次実行なら最初の呼び出しが
 * 他の到着を待ち続けてタイムアウトし、{@link #allOverlapped()}がfalseになる。
 */
final class OverlapProbe {

    private final CountDownLatch arrivals;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private volatile boolean timedOut;

    OverlapProbe(int expected) {
        this.arrivals = new CountDownLatch(expected);
    }

    /** 呼び出し側(モックのanswer)から呼ぶ。全員がそろったらtrue、待ちきれなければfalse。 */
    boolean enter() {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        arrivals.countDown();
        try {
            boolean all = arrivals.await(1500, TimeUnit.MILLISECONDS);
            if (!all) {
                timedOut = true;
            }
            return all;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timedOut = true;
            return false;
        } finally {
            inFlight.decrementAndGet();
        }
    }

    boolean allOverlapped() {
        // 1本でも他の到着を待ちきれなかったなら、その呼び出しは他と重なっていない
        return !timedOut && arrivals.getCount() == 0;
    }

    int maxInFlight() {
        return maxInFlight.get();
    }
}
