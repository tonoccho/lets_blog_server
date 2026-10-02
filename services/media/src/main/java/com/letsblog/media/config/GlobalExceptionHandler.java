package com.letsblog.media.config;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.media.ai.AiServiceException;
import com.letsblog.media.render.RechartsRenderException;
import com.letsblog.media.service.CmsBridgeException;
import com.letsblog.media.service.DiagramNotFoundException;
import com.letsblog.media.service.ForbiddenException;
import com.letsblog.media.service.GeneratedImageNotFoundException;
import com.letsblog.media.service.GenerationJobBridgeException;
import com.letsblog.media.service.IdentityServiceUnavailableException;
import com.letsblog.media.service.InvalidPagingParameterException;
import com.letsblog.media.service.ProhibitedContentException;
import com.letsblog.media.service.UnsupportedBatchSizeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * legacy-apiのGlobalExceptionHandler(#573でmedia-serviceへ移設した機能に対応する部分)を踏襲する。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * issue #1468: @PathVariable / @RequestParam の型変換失敗(未知のenum値、数値項目への非数値など)は
     * クライアントの入力誤りなので400で返す。専用ハンドラが無いと、Springは原因連鎖を辿って
     * 下の IllegalArgumentException ハンドラに一致してしまう。内部のクラス名や例外文面は
     * 漏らさず、パラメータ名だけを示す。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("パラメータ '" + e.getName() + "' の値が不正です。"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    /**
     * issue #1102: プロバイダ別のbatchSize上限超過。リクエストの誤りなので400で返し、
     * メッセージにプロバイダ名と上限を含める(利用者が枚数を直せるようにするため)。
     */
    @ExceptionHandler(UnsupportedBatchSizeException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedBatchSize(UnsupportedBatchSizeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    /**
     * issue #532 の不適切コンテンツフィルタがプロンプトをブロックしたとき。
     *
     * <p>状態コードと本文は #532 の実装時点からの契約(400と理由の文面)をそのまま復元したもの。
     * #583 の legacy-api 解体で {@link ProhibitedContentException} が media-service へ移った際、
     * legacy-api の {@code GlobalExceptionHandler} が持っていたこのハンドラだけが移設されず、
     * <b>ブロックが素の 500 Internal Server Error になっていた</b>(issue #936 で検出)。
     * 利用者には理由が何も出ず、フィルタが働いたのかサーバーが壊れたのかも区別できない。
     *
     * <p>400 なのは、直せるのは利用者が送ったプロンプトの側だからである。
     */
    @ExceptionHandler(ProhibitedContentException.class)
    public ResponseEntity<ErrorResponse> handleProhibitedContent(ProhibitedContentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    /** issue #1135: /api/render/**の本文サイズ上限超過。直せるのは送り手なので413で上限つきの理由を返す。 */
    @ExceptionHandler(RequestBodyTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleRequestBodyTooLarge(RequestBodyTooLargeException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse.of(e.getMessage()));
    }

    /** issue #1472: 生成画像一覧のlimit/offsetが範囲外。 */
    @ExceptionHandler(InvalidPagingParameterException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPagingParameter(InvalidPagingParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(GeneratedImageNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleGeneratedImageNotFound(GeneratedImageNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(DiagramNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDiagramNotFound(DiagramNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ErrorResponse> handleAiServiceException(AiServiceException e) {
        log.warn("外部レンダリングサービスの呼び出しに失敗しました", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(RechartsRenderException.class)
    public ResponseEntity<ErrorResponse> handleRechartsRenderException(RechartsRenderException e) {
        log.warn("Rechartsのレンダリングに失敗しました", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage()));
    }

    /**
     * identity-service/legacy-apiへの同期呼び出し(#573 stage3、C12(#581)までの暫定策)の失敗。
     * log-writer(#572)のIdentityServiceUnavailableExceptionハンドリングと同じ方針。
     */
    @ExceptionHandler(IdentityServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleIdentityServiceUnavailable(IdentityServiceUnavailableException e) {
        log.error("依存サービスとの同期呼び出しに失敗しました", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    /**
     * publishing-serviceのCMSブリッジ(/api/internal/publishing/**、#573 stage3でlegacy-apiに新設、
     * issue #709でpublishing-serviceへ移管)呼び出しの失敗。
     */
    @ExceptionHandler(CmsBridgeException.class)
    public ResponseEntity<ErrorResponse> handleCmsBridgeException(CmsBridgeException e) {
        log.error("publishing-serviceのCMSブリッジ呼び出しに失敗しました", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(GenerationJobBridgeException.class)
    public ResponseEntity<ErrorResponse> handleGenerationJobBridgeException(GenerationJobBridgeException e) {
        log.error("legacy-apiのGenerationJob呼び出しに失敗しました", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }
}
