package com.letsblog.ai.testsupport;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * 下流サービスごとのサーキットブレーカー({@code SyncServiceClient}がJVM全体で共有するレジストリ)を、閉じた状態へ戻す(issue #1823)。
 *
 * <p>ブレーカーは下流サービス名({@code ai-service}など)をキーにJVM全体で共有される。別のテストクラス(接続できない
 * ai-serviceへ繰り返し呼び出す統合テストなど)が開いたまま残すと、30秒間は後続のテストの呼び出しが
 * 「サーキットブレーカーが作動中」で失敗し、テストの成否が実行順に左右される。実物のクライアントを通すテストは
 * 毎回これを呼んで、他のテストの状態に依らないようにする。公開された口が無いので、レジストリを反射で取り出す
 * (プロダクションコードは変えない)。
 */
public final class SharedCircuitBreakers {

    private SharedCircuitBreakers() {
    }

    public static void reset() {
        try {
            Class<?> holder = Class.forName("com.letsblog.common.client.SyncServiceClient$SharedRegistries");
            Field field = holder.getDeclaredField("CIRCUIT_BREAKERS");
            field.setAccessible(true);
            Object registry = field.get(null);
            Method all = registry.getClass().getMethod("getAllCircuitBreakers");
            for (Object breaker : (Collection<?>) all.invoke(registry)) {
                Method reset = breaker.getClass().getMethod("reset");
                reset.setAccessible(true);
                reset.invoke(breaker);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("共有のサーキットブレーカーを戻せません", e);
        }
    }
}
