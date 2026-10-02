package com.letsblog.publishing.config;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.publishing.cms.CmsApiException;
import com.letsblog.publishing.cms.agent.AgentOperationException;
import com.letsblog.publishing.cms.agent.PostNotFoundException;
import com.letsblog.publishing.cms.ssh.SshOperationException;
import com.letsblog.publishing.github.GithubApiException;
import com.letsblog.publishing.render.MediaRenderException;
import com.letsblog.publishing.service.ArticleReviewNotFoundException;
import com.letsblog.publishing.service.BranchNotFoundException;
import com.letsblog.publishing.service.ForbiddenException;
import com.letsblog.publishing.service.IdentityServiceUnavailableException;
import com.letsblog.publishing.service.InvalidPlantUmlTagException;
import com.letsblog.publishing.service.InvalidRechartsTagException;
import com.letsblog.publishing.service.ProjectNotFoundException;
import com.letsblog.publishing.service.ProvisioningException;
import com.letsblog.publishing.service.PullRequestArticleException;
import com.letsblog.publishing.service.SiteNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * publishing-service(issue #707)のグローバル例外ハンドリング。legacy-apiのGlobalExceptionHandlerから、
 * 本サービスへ移設した例外(cms/*, PlantUML組み込みタグ)へのハンドリングのみを移設・新設する。
 * ステータスコードの割り当てはlegacy-api版と同じにしている。
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
     * サブクラス)でラップして投げる。ここで一律409 CONFLICTへ写像していたため、VSCode拡張には
     * 「対象リソースの状態が競合しています。他の端末から同じリソースを更新していないか確認して
     * ください」という、真因(画像のサイズ超過)と正反対の案内が出ていた(issue #1061のコメント、
     * 2026-09-06 02:07の実測)。
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

    @ExceptionHandler(SiteNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSiteNotFound(SiteNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProjectNotFound(ProjectNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    /** BulkManagementController/TaxonomyControllerが使う(issue #708)。 */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidPlantUmlTagException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPlantUmlTag(InvalidPlantUmlTagException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidRechartsTagException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRechartsTag(InvalidRechartsTagException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(CmsApiException.class)
    public ResponseEntity<ErrorResponse> handleCmsApiException(CmsApiException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(SshOperationException.class)
    public ResponseEntity<ErrorResponse> handleSshOperationException(SshOperationException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AgentOperationException.class)
    public ResponseEntity<ErrorResponse> handleAgentOperationException(AgentOperationException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    /**
     * 削除対象の投稿/メディアがそもそも存在しなかった場合(issue #1070)。
     * {@link SiteNotFoundException}/{@link ProjectNotFoundException}と同じ404扱いにし、
     * エージェントの疎通・実行自体の失敗({@link AgentOperationException}、502)とは区別する。
     */
    @ExceptionHandler(PostNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePostNotFound(PostNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    /** GitHub APIの失敗(認証失敗・権限不足・レート制限など、issue #1337)。汎用の500にせず、原因の分かる文面を502で返す。 */
    @ExceptionHandler(GithubApiException.class)
    public ResponseEntity<ErrorResponse> handleGithubApiException(GithubApiException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    /** PRから記事を取り出せない理由(issue #1338)。見つからない404・1 PR = 1 記事違反409・不正な記事422。 */
    @ExceptionHandler(PullRequestArticleException.class)
    public ResponseEntity<ErrorResponse> handlePullRequestArticle(PullRequestArticleException e) {
        HttpStatus status = switch (e.getKind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case MULTIPLE -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(ErrorResponse.of(e.getMessage()));
    }

    /** 提出対象のheadブランチがGitHubに無い(issue #1339)。 */
    @ExceptionHandler(BranchNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBranchNotFound(BranchNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    /** レビュー対象のPRが提出されていない(issue #1341)。 */
    @ExceptionHandler(ArticleReviewNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleArticleReviewNotFound(ArticleReviewNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(MediaRenderException.class)
    public ResponseEntity<ErrorResponse> handleMediaRenderException(MediaRenderException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    /**
     * CmsProvisioningBridgeControllerが委譲するサイトプロビジョニングの致命的な失敗(issue #710で
     * legacy-apiから移設)。CMSアダプタの解決自体に失敗した場合のみ投げられる(部分的な失敗は
     * ProvisioningResultのエラーフィールドとして200で返る)。
     */
    @ExceptionHandler(ProvisioningException.class)
    public ResponseEntity<ErrorResponse> handleProvisioningException(ProvisioningException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    /** project-service/legacy-api/content-serviceへの内部ブリッジ呼び出しの失敗(#572/#573/#574と同じ方針)。 */
    @ExceptionHandler(IdentityServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleIdentityServiceUnavailable(IdentityServiceUnavailableException e) {
        log.warn("identity-service/legacy-api/project-service/content-serviceへのブリッジ呼び出しに失敗しました: {}",
                e.getMessage());
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
