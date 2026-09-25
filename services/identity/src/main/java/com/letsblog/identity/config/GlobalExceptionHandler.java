package com.letsblog.identity.config;

import com.letsblog.identity.keycloak.KeycloakUserSyncException;
import com.letsblog.identity.service.AvatarNotFoundException;
import com.letsblog.identity.service.EmailAlreadyExistsException;
import com.letsblog.identity.service.ForbiddenException;
import com.letsblog.identity.service.InvalidRoleException;
import com.letsblog.identity.service.RoleNotFoundException;
import com.letsblog.identity.service.UnsupportedAvatarFormatException;
import com.letsblog.identity.service.UserNotFoundException;
import com.letsblog.common.web.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** サイズ超過を検知するために辿る{@code getCause()}の最大段数(循環している連鎖への保険)。 */
    private static final int MAX_CAUSE_DEPTH = 20;

    /** 413の応答メッセージに実際に設定されている上限値を載せるため(issue #1061と同じ理由)。 */
    private final MultipartProperties multipartProperties;

    public GlobalExceptionHandler(MultipartProperties multipartProperties) {
        this.multipartProperties = multipartProperties;
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(RoleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRoleNotFound(RoleNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidRoleException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRole(InvalidRoleException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    /**
     * {@code @Valid}の検証失敗(issue #993)。{@code POST /api/auth/setup}のような公開エンドポイントで
     * このハンドラが無いと、Spring MVCの既定処理に委ねられた結果、Bootの{@code ErrorPageFilter}が
     * {@code DispatcherType.ERROR}として{@code /error}へ再ディスパッチし、{@code SecurityConfig}の
     * {@code anyRequest().authenticated()}に掛かって401(認証エラー)を返してしまう。ここで
     * 通常のREQUESTディスパッチ内で解決することで、入力エラーとして素直に400を返す
     * (content/publishing等、他サービスのGlobalExceptionHandlerと同じ写像)。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage()))
                .collect(Collectors.toList());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("Validation failed", errors));
    }

    /**
     * リクエストボディがそもそもJSONとして読めない場合(不正なJSON、型不一致等、issue #993)。
     * {@link #handleValidation}と同じ理由でここに置かないとERROR再ディスパッチ経由で401になる。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("リクエストボディを読み取れません"));
    }

    /**
     * Keycloak Admin APIとの同期失敗(#562)。Keycloak停止時等に暗黙に成功させず、
     * 明確なエラー(502 Bad Gateway)として呼び出し元に伝える。
     */
    @ExceptionHandler(KeycloakUserSyncException.class)
    public ResponseEntity<ErrorResponse> handleKeycloakUserSync(KeycloakUserSyncException e) {
        log.error("Keycloak Admin APIとの同期に失敗しました", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(UnsupportedAvatarFormatException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedAvatarFormat(UnsupportedAvatarFormatException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AvatarNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAvatarNotFound(AvatarNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    /**
     * アバターアップロード(issue #1241 要件5)のmultipartサイズ超過(20MB)。platform-service
     * (issue #1061)と同じ問題・同じ対処: 未設定だとSpring Bootの既定1MBが黙って効いてしまう。
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
     * サイズ超過は413、それ以外は従来どおり未処理のまま再送出する(issue #1241。identity-serviceには
     * platform-serviceと違い{@code IllegalStateException}を409へ写像する既存の一般規約が無いため、
     * サイズ超過以外の意味づけを新設しない)。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        if (isMultipartSizeExceeded(e)) {
            log.warn("multipartアップロードのサイズ上限を超えました: {}", e.getMessage());
            return payloadTooLarge();
        }
        throw e;
    }

    /**
     * Tomcatは複数の例外型でサイズ超過を表現する(FileSizeLimitExceededException=1ファイル、
     * SizeLimitExceededException=リクエスト全体)。共通する接尾辞{@code SizeLimitExceededException}
     * だけで足りる(issue #1061と同じ判定)。
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

    /** 上限値そのものを応答に載せる。利用者が「何バイトまでなら通るのか」を応答だけで判断できるように。 */
    private ResponseEntity<ErrorResponse> payloadTooLarge() {
        String message = "アップロードされたファイルがサイズ上限を超えています(1ファイルあたり最大 "
                + multipartProperties.getMaxFileSize().toBytes() + " バイト、リクエスト全体で最大 "
                + multipartProperties.getMaxRequestSize().toBytes() + " バイトまで)";
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse.of(message));
    }
}
