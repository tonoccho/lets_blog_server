package com.letsblog.ai.domain;

/**
 * 多段レビュー(issue #1210)の実行ステップの識別子。
 *
 * <p>集合(5つ)と宣言順(=実行順)は#1210で確定済みの確定要件であり、変更しない
 * (issue #1211)。綴りは既存の{@link com.letsblog.ai.ai.AiProvider}の前例
 * (enum名をそのままAPI文字列として公開する)に揃えた。
 */
public enum ReviewStepKey {
    /** 日本語チェック: 日本語としての正しさ(文法誤り、ら抜き言葉、二重否定、助詞の誤用等)。 */
    JAPANESE,
    /** 校正チェック: 表記の正しさ(誤字脱字、表記ゆれ、送り仮名、半角/全角の不統一等)。 */
    PROOFREADING,
    /** 校閲: Web検索を伴う事実確認。 */
    FACT_CHECK,
    /** 読者視点でのチェック: 想定読者にとって前提知識の飛躍・説明不足がないか。 */
    READER_PERSPECTIVE,
    /** 文体チェック: トーンと読み口(文末表現の統一、一文の長さ、受動態の多用等)。 */
    STYLE
}
