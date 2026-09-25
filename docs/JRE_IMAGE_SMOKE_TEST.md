# JVM 実行イメージ(JRE)の起動スモークテスト

`scripts/smoke_jre_image.py`(#1108)。各 JVM サービスの**実行イメージをビルドして起動し、
`/actuator/health` が `UP` を返す**ことを確かめる。

## なぜ要るのか

`./gradlew test` はホストの full JDK で走るが、実行ステージの `eclipse-temurin:21-jre` には
`jdk.random`・`jdk.compiler`・`jdk.jdi`・`jdk.attach`・`java.instrument` などが無い。
JDK にしか無い API に依存すると、テストもカバレッジもレビューも緑のまま、イメージだけが
起動不能になる。#1101 では `RandomGenerator.getDefault()` が `jdk.random` を要し、
`@Component` の生成失敗で media-service の全 API が停止した。単体テストは、この差を
構造的に検出できない。

## 使い方

```bash
python3 scripts/smoke_jre_image.py                 # media だけ(既定)
python3 scripts/smoke_jre_image.py ai media        # 名前で指定
python3 scripts/smoke_jre_image.py all             # services/*/Dockerfile を持つ全サービス
python3 scripts/smoke_jre_image.py --changed       # origin/develop との差分から対象を導出
python3 scripts/smoke_jre_image.py --changed main  # 比較先を指定
```

終了コードは全対象が UP なら 0、1つでも UP にならなければ 1。失敗時はコンテナログの末尾を出す。

`DOCKER_CONFIG` は未設定なら `~/.config/docker-cli` にする(#1084)。別の場所を使うなら
`DOCKER_CONFIG=... python3 scripts/smoke_jre_image.py` のように渡す。

`--changed` は `BASE...HEAD` の差分と未コミットの変更から対象を決める。`services/<name>/` は
そのサービス、`packages/`・`gradle/`・`config/`・ルートの `build.gradle` / `settings.gradle` /
`gradlew` は全サービス、それ以外(`apps/` や `docs/` だけの変更)は対象なし。

## いつ回すか

- **JVM サービスのコード・`build.gradle`・`Dockerfile` を変え、Merge Request を開く前**。
  `--changed` で足りる。
- JDK 標準ライブラリの新しい API を使い始めたとき(乱数・時刻・プロセス・リフレクション周り)。
- ベースイメージや Gradle・Spring Boot を更新したとき(`all`)。

コミット単位のフックや CI には組み込まない(ビルドと起動を伴い重いため。#1108 Out of Scope)。

## 何を起動するか・後始末

依存(MySQL 8 と RabbitMQ)は使い捨てのコンテナを1組だけ立て、対象サービスは順に同じ組へ
つなぐ。Keycloak や他サービスは起動しない(`/actuator/health` の判定に要らず、JWKS 取得も
起動時ではなくリクエスト時のため)。

- 実行ごとに run id を採番し、ネットワーク・コンテナ・イメージをすべて
  `lbs-smoke=<run id>` ラベル/`lbs-smoke-<run id>` 名前空間で作る。
- ホストのポートは公開せず、`lbs-*` の固定コンテナ名も使わない。稼働中の開発環境と衝突しない。
- 終了時(失敗・Ctrl-C を含む)に、そのラベルのものだけを `finally` で削除する。

## 所要時間(実測、2026-09-26)

`docker` のビルドキャッシュが無い状態(この実行の最初)に近い値。Gradle の依存取得を含む。

| 対象 | 依存の起動 | イメージのビルド | 起動〜healthy | 合計 |
| --- | --- | --- | --- | --- |
| media | 24s | 270s(別の回は 222s) | 17s(別の回は 14s) | 約 5分12秒(別の回は 4分18秒) |

| media(ビルドキャッシュあり) | 20s | 12s | 14s | 48s |

ビルドが支配的(Gradle の `bootJar` と Playwright の Chromium 導入)。1サービス約 4〜5分なので、
10サービスを毎回回すのは現実的でない。既定を `media` 1本にし、通常は `--changed` で
変更したサービスだけを回す。他サービスの実測は、対象に加えた時点でここへ追記する。

**検証済みの範囲**: `media` のみ。他サービスは `SPRING_DATASOURCE_*` と `RABBITMQ_HOST` だけを
渡すので、追加の必須設定(シークレット等)を持つサービスでは起動に失敗しうる。その場合は
`smoke_jre_image.py` の `service_run_cmd` に環境変数を足す。

## #1101 の再現

`SeedResolver` の `this(new Random())` を `this(RandomGenerator.getDefault())` に一時的に戻すと、
スモークテストは `FAIL`(終了コード 1)になり、ログに次が出る。

```
Caused by: java.lang.IllegalArgumentException:
  No implementation of the random number generator algorithm "L32X64MixRandom" is available
	at java.base/java.util.random.RandomGenerator.getDefault(Unknown Source)
	at com.letsblog.media.ai.SeedResolver.<init>(SeedResolver.java:50)
```

戻すと `PASS health={"groups":["liveness","readiness"],"status":"UP"}`。

## テスト

純粋部分(対象の決定・コマンド組み立て・ヘルス判定・後始末の範囲)は
`scripts/test_smoke_jre_image.py`。
