package com.letsblog.common.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 手動でのみ時刻が進むテスト用の{@link Clock}。
 *
 * <p>{@link CircuitBreaker}のクールダウン判定は経過時間そのものが検証対象のため、実時間に依存させると
 * 「テストを書くための処理(メソッド参照の生成やJUnitのリフレクション呼び出し)にかかる時間」が
 * クールダウンと同じオーダーになり、結果が実行環境の速度で変わってしまう。この時刻源を使うと
 * 時間が進むのは{@link #advance(Duration)}を呼んだときだけになる。
 */
final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /** エポックを起点とする時刻源を作る。起点の値自体はテストの関心事ではない。 */
    static MutableClock atEpoch() {
        return new MutableClock(Instant.EPOCH, ZoneOffset.UTC);
    }

    /** 時刻を指定した分だけ進める。 */
    void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
