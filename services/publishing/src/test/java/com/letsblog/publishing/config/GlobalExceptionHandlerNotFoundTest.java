package com.letsblog.publishing.config;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.publishing.cms.agent.PostNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler}の{@link PostNotFoundException}の写像(issue #1070)。
 *
 * <p>存在しない投稿/メディアの削除は、疎通・実行自体の失敗を表す{@code AgentOperationException}(502)
 * ではなく、{@code SiteNotFoundException}/{@code ProjectNotFoundException}と同じ404として扱う。
 */
@DisplayName("publishing-service: GlobalExceptionHandlerのPostNotFoundExceptionの写像(issue #1070)")
class GlobalExceptionHandlerNotFoundTest {

    private GlobalExceptionHandler handler() {
        return new GlobalExceptionHandler(new MultipartProperties());
    }

    @Test
    @DisplayName("PostNotFoundExceptionは404になり、メッセージがそのまま応答に載る")
    void postNotFoundExceptionは404() {
        final ResponseEntity<ErrorResponse> response =
                handler().handlePostNotFound(new PostNotFoundException("投稿 '999999999' が見つかりません"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("投稿 '999999999' が見つかりません");
    }
}
