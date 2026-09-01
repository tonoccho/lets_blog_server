package com.letsblog.common.messaging;

/**
 * ドメインイベント(issue #580)の冪等な受信を保証するための「処理済みevent_id」の記録先。
 * 各コンシューマーサービスが自分のスキーマ内にJPAエンティティ+リポジトリで実装する
 * (processed_eventsテーブル、event_idにUNIQUE制約。lbs-common自体はSpring Bean/JPA依存を
 * 持たない方針(ai-service以外はcom.letsblog.commonをスキャンしない、既存の各サービスの
 * ApplicationクラスのJavadoc参照)のため、インターフェースのみをここに定義する)。
 */
public interface ProcessedEventStore {

    /**
     * event_idを新規に記録しようと試みる。呼び出し元(RabbitListenerメソッド)が
     * {@code @Transactional}であることを前提とし、実装はそのトランザクションに参加すること。
     *
     * <p>推奨実装: まずevent_idの存在確認(existsById)を行い、未処理ならUNIQUE制約付きの行を
     * save()するだけで即時flushはしない(ビジネスロジックと同じトランザクション内でまとめて
     * 最終コミット時にflushさせる)。複数インスタンスでの水平スケール時、存在確認後に別
     * インスタンスが同じevent_idを先にコミットする稀な競合が起きても、コミット時のUNIQUE制約
     * 違反でトランザクション全体(ビジネスロジックの効果を含む)がロールバックされ、再配信後の
     * 次回試行でexistsByIdがtrueを返して正しくスキップされるため、正しさが保たれる
     * (即時INSERT+例外捕捉のパターンは、捕捉した例外がJPA実装によってはトランザクション/
     * 永続化コンテキストの状態を壊しうるため避けること)。
     *
     * @return 今回新規に記録できた(=未処理だった)ならtrue、既に記録済み(=重複配信)ならfalse
     */
    boolean markIfNotProcessed(String eventId, String eventType);
}
