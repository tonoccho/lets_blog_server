package com.letsblog.platform.config;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.platform.service.BackupException;
import com.letsblog.platform.service.ComputeDeviceException;
import com.letsblog.platform.service.ForbiddenException;
import com.letsblog.platform.service.IdentityServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * legacy-apiのGlobalExceptionHandlerのうち、platform-serviceが移設対象とした例外(issue #693、
 * システム設定の更新バリデーション・admin権限チェック。issue #694でBackupServiceと共に
 * BackupExceptionのハンドリングも追加)へのハンドリングのみを移設する。
 * ステータスコードの割り当てはlegacy-api版と同じにしている(content-service/project-serviceと
 * 同じ方針)。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** サイズ超過を検知するために辿る{@code getCause()}の最大段数(循環している連鎖への保険)。 */
    private static final int MAX_CAUSE_DEPTH = 20;

    /** 413の応答メッセージに実際に設定されている上限値を載せるため(issue #1061)。 */
    private final MultipartProperties multipartProperties;

    public GlobalExceptionHandler(MultipartProperties multipartProperties) {
        this.multipartProperties = multipartProperties;
    }

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

    /**
     * multipartのサイズ超過(issue #1061)。
     *
     * <p>Springの{@code StandardMultipartHttpServletRequest}がパース失敗をこの型へ変換した場合に
     * 到達する。Tomcatが投げた例外がこの型へ変換されずに素の{@link IllegalStateException}のまま
     * 届く経路もあるため、{@link #handleIllegalState}側でも同じ判定を行う。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("multipartアップロードのサイズ上限を超えました: {}", e.getMessage());
        return payloadTooLarge();
    }

    /**
     * サイズ超過は413、それ以外は従来どおり409(issue #1061)。
     *
     * <p>Tomcatの{@code Request.parseParts()}はmultipartのサイズ超過を
     * {@code org.apache.tomcat.util.http.InvalidParameterException}({@link IllegalStateException}の
     * サブクラス)でラップして投げる。ここで一律409 CONFLICTへ写像していたため、
     * {@code POST /api/backup/restore}がサイズ超過で失敗しても「競合」としか返らなかった
     * (publishing-serviceで実測された問題と同じ構造。issue #1061)。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        if (isMultipartSizeExceeded(e)) {
            log.warn("multipartアップロードのサイズ上限を超えました: {}", e.getMessage());
            return payloadTooLarge();
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    /** 上限値そのものを応答に載せる。利用者が「何バイトまでなら通るのか」を応答だけで判断できるように。 */
    private ResponseEntity<ErrorResponse> payloadTooLarge() {
        final String message = "アップロードされたファイルがサイズ上限を超えています(1ファイルあたり最大 "
                + multipartProperties.getMaxFileSize().toBytes() + " バイト、リクエスト全体で最大 "
                + multipartProperties.getMaxRequestSize().toBytes() + " バイト)。";
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse.of(message));
    }

    /**
     * 例外の連鎖のどこかがmultipartのサイズ超過かを判定する。
     *
     * <p>Tomcatはサイズ超過を{@code org.apache.tomcat.util.http.fileupload.impl.SizeException}の
     * 具象サブクラス({@code FileSizeLimitExceededException}(1ファイルの超過)/
     * {@code SizeLimitExceededException}(リクエスト全体の超過))として投げる。これらを
     * {@code instanceof}で見ないのは、サーブレットコンテナの内部クラスへ本番コードをコンパイル時に
     * 束縛しないため。Spring自身も{@code StandardMultipartHttpServletRequest#handleParseFailure}で
     * 同じ判定をメッセージの文字列一致で行っているが、メッセージよりクラス名の方が安定している。
     * 抽象クラスである{@code SizeException}そのものは実体化されないので、判定は具象2クラスに
     * 共通する接尾辞{@code SizeLimitExceededException}だけで足りる。
     */
    private static boolean isMultipartSizeExceeded(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof MaxUploadSizeExceededException) {
                return true;
            }
            if (current.getClass().getSimpleName().endsWith("SizeLimitExceededException")) {
                return true;
            }
            final Throwable cause = current.getCause();
            if (cause == current) {
                return false;
            }
            current = cause;
        }
        return false;
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage()));
    }

    /** 演算デバイス切り替えを受け付けられない(issue #1399)。例外自身のHTTPステータスで返す。 */
    @ExceptionHandler(ComputeDeviceException.class)
    public ResponseEntity<ErrorResponse> handleComputeDevice(ComputeDeviceException e) {
        return ResponseEntity.status(e.status()).body(ErrorResponse.of(e.getMessage()));
    }

    /** バックアップ/リストア処理(mysqldump/mysql/pg_dump/pg_restore)の失敗(issue #694)。 */
    @ExceptionHandler(BackupException.class)
    public ResponseEntity<ErrorResponse> handleBackupException(BackupException e) {
        log.error("バックアップ/リストア処理に失敗しました", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of(e.getMessage()));
    }

    /** identity-serviceへの内部ブリッジ呼び出しの失敗(#572/#573/#574/#576と同じ方針)。 */
    @ExceptionHandler(IdentityServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleIdentityServiceUnavailable(IdentityServiceUnavailableException e) {
        log.warn("identity-serviceへのブリッジ呼び出しに失敗しました: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("Validation failed", errors));
    }
}
