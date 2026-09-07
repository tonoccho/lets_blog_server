package com.letsblog.media.dto;

import java.util.List;

/**
 * 1リクエスト分の画像生成結果。{@code images}には{@code batchSize × batchCount}枚
 * (最大256枚)が、リピート順・バッチ内順に並ぶ。
 *
 * <p>{@code failedRepeats}はissue #1102で追加した、失敗したリピートの回数。
 * リピートの途中でプロバイダが失敗しても、それまでに成功した画像は捨てずに返す
 * (200枚生成したあとの1回の失敗で全部を失うのは損失が大きすぎる)。そのため
 * 「頼んだ枚数より少ない」状態が正常応答として起こりうるので、何回落ちたのかを
 * 結果に含める。全リピートが失敗した場合だけ、例外として呼び出し元へ返す。
 *
 * <p>{@code failedRepeats}には、決定的な失敗が続いたために<b>打ち切って一度も
 * 試さなかった</b>リピートも含む。こうすると
 * {@code 成功リピート数 + failedRepeats = 要求したbatchCount}が常に成り立ち、
 * 「頼んだ枚数に足りない」ぶんを数えるだけで追える。実際にプロバイダを呼んだ回数と
 * 打ち切りの有無は{@code generation_jobs}の結果ペイロード
 * ({@code attemptedRepeats} / {@code aborted})に残る。
 */
public record AiImageBatchResponse(List<AiImageResponse> images, int failedRepeats) {
}
