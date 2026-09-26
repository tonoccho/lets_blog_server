package com.letsblog.identity.config;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.identity.client.PublishingServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1136: サイト側の著者登録(publishing-service呼び出し)が失敗したとき、
 * メンバー追加API等が500ではなく原因の分かる502を返す。
 *
 * <p>受け入れテスト(Gherkin)ではなくサービス単体テストで表す。「実WordPressに到達できない
 * サイトが紐づいたプロジェクト」はWeb UIから作れない内部契約のため。
 */
@DisplayName("identity-service: GlobalExceptionHandlerのpublishing-service呼び出し失敗の写像(issue #1136)")
class GlobalExceptionHandlerPublishingTest {

    private GlobalExceptionHandler handler() {
        return new GlobalExceptionHandler(new MultipartProperties());
    }

    @Test
    @DisplayName("PublishingServiceExceptionは502になり、原因メッセージが応答に含まれる")
    void publishing呼び出し失敗は502() {
        ResponseEntity<ErrorResponse> response = handler().handlePublishingService(
                new PublishingServiceException("publishing-serviceの著者プロビジョニング呼び出しに失敗しました: 500", null));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().error().contains("著者プロビジョニング"));
    }
}
